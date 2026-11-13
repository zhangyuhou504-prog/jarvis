package com.example.jarvis.infrastructure.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmClientService} 默认实现：基于 {@link LlmClientFactory.ResolvedLlmClient}
 * 携带的 WebClient 直连平台 OpenAI 兼容接口。
 *
 * <p>调用方不感知底层是 MXBC 网关还是 SiliconFlow——平台差异全部收敛在
 * {@code ResolvedLlmClient}（baseUrl + 鉴权头 + 模型参数）里。</p>
 */
@Service
@Slf4j
public class LlmClientServiceImpl implements LlmClientService {

    /**
     * 共享 JSON 解析器（线程安全）。
     */
    private static final JsonMapper JSON = new JsonMapper();

    @Override
    public Mono<JsonNode> chat(LlmClientFactory.ResolvedLlmClient client, ObjectNode request) {
        return client.webClient()
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .map(raw -> {
                    if (raw == null || raw.isBlank()) {
                        throw new IllegalStateException("模型返回为空");
                    }
                    try {
                        return JSON.readTree(raw);
                    } catch (Exception e) {
                        throw new IllegalStateException("模型响应解析失败: " + e.getMessage(), e);
                    }
                });
    }
}
