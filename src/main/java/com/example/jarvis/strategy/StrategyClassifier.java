package com.example.jarvis.strategy;

import com.example.jarvis.config.StrategyProperties;
import com.example.jarvis.domain.ChatMessage;
import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * 小模型执行策略判断器（第八阶段 8.2.6）。
 *
 * <p><b>职责（只有四件事）</b>：接收 {@link StrategyContext} → 组装策略判断
 * Prompt 与用户上下文 → 调用配置指定的小模型 → 把模型返回的 JSON 转成
 * {@link StrategyResult}。不负责 Java 规则判断，也不负责最终策略分流
 * （那是 StrategyResolver 的职责）。</p>
 *
 * <p><b>为什么必须复用 {@link LlmClientService}</b>：模型 HTTP 调用层已统一封装
 * （超时/异常/响应解析），策略判断与意图识别、Planner 共用同一调用层，
 * 不再另造 WebClient 请求。</p>
 *
 * <p><b>失败处理（与意图识别的关键差异）</b>：意图识别失败回落 UNKNOWN 不向上抛
 * （路由退化为默认模型即可）；但策略判断不同——<b>不能在异常时默认返回 DIRECT</b>，
 * 否则可能把本应规划的复杂任务直接执行。因此任何失败（模型无返回、内容为空、
 * JSON 不合法、策略值非 DIRECT/PLANNED）都抛 {@link IllegalStateException}
 * 或 {@link IllegalArgumentException}，明确交给上层处理。</p>
 */
@Component
@Slf4j
public class StrategyClassifier {

    /**
     * 合法策略值集合（DIRECT / PLANNED），模型返回其他值一律拒绝。
     */
    private static final Set<String> VALID_STRATEGIES =
            EnumSet.allOf(ExecutionStrategy.class).stream()
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * 策略判断模型调用超时（秒）。分类输出很短，与意图识别同量级。
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final StrategyProperties strategyProperties;
    private final LlmClientFactory llmClientFactory;
    private final LlmClientService llmClientService;
    private final ObjectMapper objectMapper;

    /**
     * 策略判断 Prompt（启动时一次性读入 {@code classpath:prompts/strategy-classification-prompt.md}）。
     * Prompt 与 Java 代码分离：调整判断规则只改 Markdown，不重新编译。
     */
    private final Resource classifierPromptResource;
    private String classifierPrompt;

    public StrategyClassifier(
            StrategyProperties strategyProperties,
            LlmClientFactory llmClientFactory,
            LlmClientService llmClientService,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "classpath:prompts/strategy-classification-prompt.md")
            Resource classifierPromptResource) {
        this.strategyProperties = strategyProperties;
        this.llmClientFactory = llmClientFactory;
        this.llmClientService = llmClientService;
        this.objectMapper = objectMapper;
        this.classifierPromptResource = classifierPromptResource;
    }

    /**
     * 启动时一次性读入策略判断 Prompt（fail-fast：文件缺失启动即报错）。
     */
    @PostConstruct
    public void init() throws IOException {
        try (InputStream in = classifierPromptResource.getInputStream()) {
            this.classifierPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        log.info("策略判断 Prompt 加载完成, 长度={}", classifierPrompt.length());
    }

    /**
     * 用小模型判断执行策略。
     *
     * @param context 策略判断上下文（用户消息 + 意图 + 会话历史）
     * @return 判断结果（策略 + 原因）
     * @throws IllegalStateException    模型无返回 / 内容为空 / JSON 不合法
     * @throws IllegalArgumentException 策略值不是 DIRECT / PLANNED
     */
    public StrategyResult classify(StrategyContext context) {

        // 1. 获取策略判断模型客户端（modelId 未配置时回落默认模型）
        String modelId = strategyProperties.modelId();
        LlmClientFactory.ResolvedLlmClient client = llmClientFactory.getClient(modelId);

        // 2. 组装请求：system = 策略判断 Prompt，user = 结构化上下文 JSON
        ObjectNode request = buildRequest(client, context);

        // 3. 非流式调用，block 等待完整响应
        JsonNode response = llmClientService
                .chat(client, request)
                .block(CALL_TIMEOUT);
        if (response == null) {
            throw new IllegalStateException("策略判断模型调用没有返回结果");
        }

        String content = response
                .path("choices").path(0).path("message").path("content")
                .asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("策略判断模型没有返回有效内容");
        }

        // 4. 解析并校验 JSON（非法值明确报错，不默认 DIRECT）
        return parseResult(content);
    }

    /**
     * 组装模型请求体。
     *
     * <p>system 是策略判断 Prompt；user 是结构化上下文（用户消息、意图、
     * 最近历史），让模型在完整任务语境下判断，而不是只看一句话。</p>
     */
    private ObjectNode buildRequest(
            LlmClientFactory.ResolvedLlmClient client, StrategyContext context) {

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", client.modelName());
        request.put("stream", false);

        if (client.temperature() != null) {
            request.put("temperature", client.temperature());
        }
        if (client.maxTokens() != null) {
            request.put("max_tokens", client.maxTokens());
        }
        if (client.enableThinking() != null) {
            request.put("enable_thinking", client.enableThinking());
        }

        ArrayNode messages = request.putArray("messages");
        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", classifierPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        // content 必须是字符串（OpenAI 兼容协议）：结构化上下文 JSON 序列化为文本下发，
        // 直接塞 JsonNode 会是对象类型，网关直接 400
        userMessage.put("content", buildUserContent(context).toString());
        return request;
    }

    /**
     * 构建用户上下文 JSON（作为 user 消息内容）。
     *
     * <p>把 StrategyContext 序列化成 JSON 而非纯文本拼接，字段结构固定，
     * 模型解析稳定；intent 为 null 时写入 null（明确表达"未识别"而非缺字段）。</p>
     */
    private JsonNode buildUserContent(StrategyContext context) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("userMessage", context.userMessage());
        if (context.intentResult() != null) {
            content.put("intent", context.intentResult().intent() == null
                    ? null
                    : context.intentResult().intent().name());
        } else {
            content.putNull("intent");
        }
        ArrayNode history = content.putArray("history");
        for (ChatMessage message : context.history()) {
            ObjectNode item = history.addObject();
            item.put("role", message.getRole());
            item.put("content", message.getContent());
        }
        return content;
    }

    /**
     * 解析模型输出为 StrategyResult（剥围栏 + 合法性校验）。
     *
     * <p>校验项：strategy 必须是 DIRECT / PLANNED；reason 不能为空。
     * 失败明确抛异常（含原始输出），交给上层处理，不静默回退 DIRECT。</p>
     */
    private StrategyResult parseResult(String content) {
        String json = extractJsonObject(content);
        try {
            JsonNode root = objectMapper.readTree(json);
            String strategy = root.path("strategy").asText(null);
            if (strategy == null || strategy.isBlank()) {
                throw new IllegalArgumentException("策略判断结果缺少 strategy 字段: " + json);
            }
            if (!VALID_STRATEGIES.contains(strategy)) {
                throw new IllegalArgumentException("未知策略值: " + strategy + "，合法值: DIRECT / PLANNED");
            }
            String reason = root.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("策略判断结果缺少 reason 字段: " + json);
            }
            return new StrategyResult(ExecutionStrategy.valueOf(strategy), reason);
        } catch (JacksonException e) {
            throw new IllegalStateException("策略判断返回的 JSON 不合法: " + content, e);
        }
    }

    /**
     * 从模型输出中提取最外层 JSON 对象（容忍 markdown 围栏与解释文字）。
     */
    private String extractJsonObject(String content) {
        String text = content.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("策略判断输出中未找到 JSON 对象: " + content);
        }
        return text.substring(start, end + 1);
    }
}
