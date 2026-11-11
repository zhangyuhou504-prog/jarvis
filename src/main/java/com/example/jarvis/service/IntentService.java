package com.example.jarvis.service;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.model.IntentResult;
import com.example.jarvis.model.IntentType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图识别服务：调用大模型对用户输入进行意图分类，返回结构化 {@link IntentResult}。
 *
 * <p>模型调用复用 {@link LlmClientFactory}（第四阶段多平台模型层），走<b>非流式</b>请求
 * （{@code stream:false}），一次拿到完整响应。</p>
 *
 * <p><b>健壮性原则</b>：模型返回的 JSON 不保证合法、也不保证符合我们的类型，
 * 因此 {@link #recognize} 全程兜底——网络失败、响应无 content、JSON 不合法、
 * 意图值不在 5 类枚举内、字段缺失等<b>任何</b>异常都回落 {@link IntentType#UNKNOWN}，
 * 绝不向调用方抛异常、绝不返回 null。</p>
 *
 * <p>分类 Prompt 来自独立文件 {@code classpath:prompts/intent-classify.md}
 * （含 5 类定义与边界判定规则），要求模型只输出严格 JSON。</p>
 */
@Service
@Slf4j
public class IntentService {

    /**
     * 共享 JSON 解析器（线程安全）。
     */
    private static final JsonMapper JSON = new JsonMapper();

    private final LlmClientFactory llmClientFactory;

    /**
     * 意图分类 Prompt（启动时从 Markdown 文件一次性读入）。
     */
    private final String intentClassifyPrompt;

    /**
     * 意图识别使用的模型 ID（对应 {@code jarvis.llm.models} 的 key）。
     * 为空时使用默认模型；后续可为分类单独指定一个更快/更便宜的模型。
     */
    @Value("${jarvis.intent.model-id:}")
    private String intentModelId;

    /**
     * 意图识别的模型调用超时（秒）。分类输出很短，正常几秒内完成。
     */
    @Value("${jarvis.intent.timeout-seconds:30}")
    private long intentTimeoutSeconds;

    public IntentService(LlmClientFactory llmClientFactory,
                         @Value("classpath:prompts/intent-classify.md") Resource intentPromptResource)
            throws IOException {
        this.llmClientFactory = llmClientFactory;
        this.intentClassifyPrompt = new String(intentPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 识别用户输入的意图。
     *
     * @param userInput 用户输入
     * @return 意图识别结果；任何失败都回落 {@link IntentType#UNKNOWN}（永不返回 null）
     */
    public IntentResult recognize(String userInput) {
        try {
            // 1. 解析模型客户端（modelId 为空则用默认模型）
            LlmClientFactory.ResolvedLlmClient client = llmClientFactory.getClient(intentModelId);

            // 2. 非流式调用模型，拿到原始响应文本
            String rawResponse = callModel(client, userInput);

            // 3. 从响应中提取 choices[0].message.content
            String content = extractContent(rawResponse);

            // 4. 容错提取最外层 JSON 对象（模型偶尔会包 markdown 代码块或加解释文字）
            String json = extractJsonObject(content);

            // 5. 反序列化为 IntentResult（意图值非法/字段缺失会抛异常，被下方 catch 兜底）
            IntentResult result = JSON.readValue(json, IntentResult.class);

            // 6. 轻量校验：confidence 截断到 [0,1]，intent 为空则 UNKNOWN
            return sanitize(result);
        } catch (Exception e) {
            log.warn("意图识别失败，回落 UNKNOWN: {}", e.getMessage(), e);
            return new IntentResult(IntentType.UNKNOWN, 0.0, "意图识别失败: " + e.getMessage());
        }
    }

    /**
     * 非流式调用模型，返回原始响应体文本。
     *
     * @param client    模型客户端
     * @param userInput 用户输入
     * @return 原始响应 JSON 文本
     */
    private String callModel(LlmClientFactory.ResolvedLlmClient client, String userInput) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", client.modelName());
        body.put("stream", false);
        if (client.temperature() != null) {
            body.put("temperature", client.temperature());
        }
        // 平台扩展参数（如公司网关的 enable_thinking），与 ChatService 保持一致
        if (client.enableThinking() != null) {
            body.put("enable_thinking", client.enableThinking());
        }
        body.put("messages", List.of(
                messageOf("system", intentClassifyPrompt),
                messageOf("user", userInput)
        ));

        // 非流式：bodyToMono 拿到完整响应；在 servlet 线程上 block 安全
        String raw = client.webClient()
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(intentTimeoutSeconds));
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("模型返回为空");
        }
        return raw;
    }

    /**
     * 从非流式响应中提取 {@code choices[0].message.content}。
     *
     * @param rawResponse 原始响应 JSON
     * @return 模型输出内容
     */
    private String extractContent(String rawResponse) throws Exception {
        JsonNode node = JSON.readTree(rawResponse);
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new IllegalStateException("响应缺少 choices");
        }
        String content = choices.get(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new IllegalStateException("模型未返回内容");
        }
        return content;
    }

    /**
     * 从模型输出中提取最外层 JSON 对象。
     *
     * <p>容忍模型偶尔输出 markdown 代码块（```json ... ```）或在 JSON 前后加解释文字：
     * 直接截取第一个 {@code {} 到最后一个 {@code }} 之间的内容。</p>
     *
     * @param content 模型输出
     * @return 最外层 JSON 对象字符串
     */
    private String extractJsonObject(String content) {
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("模型输出中未找到 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    /**
     * 轻量校验与归一化：confidence 截断到 [0,1]，intent 为空回落 UNKNOWN。
     *
     * @param result 反序列化结果
     * @return 归一化后的结果
     */
    private IntentResult sanitize(IntentResult result) {
        double confidence = result.confidence() == null ? 0.0 : result.confidence();
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        if (result.intent() == null) {
            return new IntentResult(IntentType.UNKNOWN, 0.0, result.reason());
        }
        return new IntentResult(result.intent(), confidence, result.reason());
    }

    /**
     * 构建单条消息。
     *
     * @param role    角色
     * @param content 内容
     * @return 消息 Map
     */
    private Map<String, String> messageOf(String role, String content) {
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }
}
