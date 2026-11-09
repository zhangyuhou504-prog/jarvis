package com.example.jarvis.service;

import com.example.jarvis.config.AiConfig;
import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.exception.AiServiceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 对话业务：调用大模型生成回答。
 *
 * <p>非流式走 Spring AI {@link ChatClient}；流式走直连网关的 {@link WebClient}，
 * 自行解析 SSE，规避网关精简 chunk 与 Spring AI 2.0 SDK 严格校验不兼容的问题。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final WebClient gatewayWebClient;

    /**
     * 用于解析 SSE chunk 的共享 JSON 解析器（线程安全）。
     */
    private static final JsonMapper JSON = new JsonMapper();

    /**
     * 流式：相邻两个 chunk 之间的最大等待时间（秒）。
     * 覆盖“连接挂起”与“生成中断”两种长时间无响应场景。
     */
    @Value("${jarvis.chat.stream-element-timeout-seconds:60}")
    private long streamElementTimeoutSeconds;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    /**
     * 处理一轮对话，返回模型回答（非流式）。
     *
     * @param request 对话请求
     * @return 包含模型回答的响应
     */
    public ChatResponse chat(ChatRequest request) {
        log.info("收到对话请求: {}", request.getMessage());
        String answer;
        try {
            answer = chatClient.prompt()
                    .user(request.getMessage())
                    .call()
                    .content();
        } catch (Exception e) {
            // 完整堆栈只进日志；对客户端只暴露友好提示（由 GlobalExceptionHandler 转 502）
            log.error("模型调用失败: {}", e.getMessage(), e);
            throw new AiServiceException("模型服务暂时不可用，请稍后重试", e);
        }
        log.info("模型回答完成, 长度={}", answer == null ? 0 : answer.length());
        return new ChatResponse(answer);
    }

    /**
     * 流式对话：以响应式流逐段返回模型生成的文本。
     *
     * <p>直接请求网关 {@code /chat/completions}（{@code stream=true}），
     * 逐条解析 SSE 数据中的 {@code choices[0].delta.content}。</p>
     *
     * <p>生命周期：{@code doOnNext} 旁路收集完整答案；{@code timeout} 控制元素间超时；
     * {@code doOnError} 识别流中异常；{@code onErrorResume} 在响应已提交后优雅收尾；
     * {@code doFinally} 在 onComplete/onError/cancel（客户端断连）时统一收尾并释放。</p>
     *
     * @param request 对话请求
     * @return 文本分片流，每个元素为模型生成的一小段文本
     */
    public Flux<String> chatStream(ChatRequest request) {
        log.info("收到流式对话请求: {}", request.getMessage());
        // 任务2：旁路收集完整答案。Reactor 对同一订阅的 onNext 串行回调，StringBuilder 无需加锁。
        // 这是将来接 Memory/DB 落库的位置。
        StringBuilder fullAnswer = new StringBuilder();
        // 任务1：标记是否发生过异常，便于 doFinally 区分结束原因。
        AtomicBoolean errored = new AtomicBoolean(false);

        return gatewayWebClient.post()
                .uri("/chat/completions")
                .bodyValue(streamBody(request.getMessage()))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .map(this::extractContent)
                .filter(content -> !content.isEmpty())
                // 任务2：每个 chunk 旁路拼接到完整答案（实时推送与收集并行）
                .doOnNext(fullAnswer::append)
                // 任务1：元素间超时——订阅后或上一个 chunk 后超过该时长无新数据即中断
                .timeout(Duration.ofSeconds(streamElementTimeoutSeconds))
                // 任务1：识别流中异常（模型调用失败 / 生成中报错 / 超时）
                .doOnError(err -> {
                    errored.set(true);
                    log.error("流式回答异常, 已收集长度={}: {}", fullAnswer.length(), err.getMessage(), err);
                })
                // 任务3：流中错误兜底——响应已提交无法改状态码，转为友好提示并优雅结束
                .onErrorResume(err -> Flux.just("[提示] 回答生成中断，请稍后重试"))
                // 任务1：流正常收尾（含被 onErrorResume 恢复后的收尾）
                .doOnComplete(() -> log.info("流式回答收尾, 完整答案长度={}, 内容={}",
                        fullAnswer.length(), fullAnswer))
                // 任务1：最终释放——onComplete / onError / cancel（客户端断连）都会走到这里。
                // 客户端断连时 Reactor 的 cancel 向上传播、取消上游 WebClient 请求，不再做无谓后续工作。
                .doFinally(signal -> log.info("流式结束, 信号={}, 是否异常={}, 已收集长度={}",
                        signal, errored.get(), fullAnswer.length()));
    }

    /**
     * 构建流式请求体（OpenAI 兼容格式）。
     *
     * @param message 用户消息
     * @return 请求体
     */
    private Map<String, Object> streamBody(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", true);
        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", AiConfig.SYSTEM_PROMPT);
        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", message);
        body.put("messages", List.of(systemMsg, userMsg));
        return body;
    }

    /**
     * 从一条 SSE 数据中提取文本内容。
     *
     * <p>兼容网关两种 chunk 格式：标准 chunk 与精简 chunk
     * （后者不带 {@code id} 等字段，只有 {@code choices[0].delta.content}）。</p>
     *
     * @param data SSE 事件数据（JSON 字符串，或 {@code [DONE]}）
     * @return 文本内容；非文本事件返回空串
     */
    private String extractContent(String data) {
        String json = data.trim();
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("delta").path("content").asText("");
            }
        } catch (Exception e) {
            log.warn("解析 SSE chunk 失败: {}", e.getMessage());
        }
        return "";
    }
}
