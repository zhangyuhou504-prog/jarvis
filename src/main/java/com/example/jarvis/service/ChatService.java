package com.example.jarvis.service;

import com.example.jarvis.config.AiConfig;
import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    /**
     * 处理一轮对话，返回模型回答。
     *
     * @param request 对话请求
     * @return 包含模型回答的响应
     */
    public ChatResponse chat(ChatRequest request) {
        log.info("收到对话请求: {}", request.getMessage());
        String answer = chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();
        log.info("模型回答完成, 长度={}", answer == null ? 0 : answer.length());
        return new ChatResponse(answer);
    }

    /**
     * 流式对话：以响应式流逐段返回模型生成的文本。
     *
     * <p>直接请求网关 {@code /chat/completions}（{@code stream=true}），
     * 逐条解析 SSE 数据中的 {@code choices[0].delta.content}。</p>
     *
     * @param request 对话请求
     * @return 文本分片流，每个元素为模型生成的一小段文本
     */
    public Flux<String> chatStream(ChatRequest request) {
        log.info("收到流式对话请求: {}", request.getMessage());
        return gatewayWebClient.post()
                .uri("/chat/completions")
                .bodyValue(streamBody(request.getMessage()))
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .map(this::extractContent)
                .filter(content -> !content.isEmpty());
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
