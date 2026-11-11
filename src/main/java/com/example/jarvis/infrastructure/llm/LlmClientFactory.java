package com.example.jarvis.infrastructure.llm;

import com.example.jarvis.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 客户端工厂：根据模型 ID 解析出所属平台连接，并创建/复用对应的 {@link WebClient}。
 *
 * <p>职责边界：</p>
 * <ul>
 *   <li>{@link LlmProperties} 保存模型与平台配置</li>
 *   <li>本工厂负责"模型 ID → 平台连接 → WebClient"的解析与复用</li>
 *   <li>后续 {@code ModelRouter} 再负责"该选哪个模型"的决策，本工厂不掺和路由</li>
 * </ul>
 *
 * <p><b>缓存策略</b>：按 {@code connectionId} 缓存 WebClient，而非按模型 ID。
 * 同一平台下的多个模型共享一个 WebClient（base-url、API Key 相同，仅请求体 model 不同），
 * 一个平台配 N 个模型也只需一个 WebClient。</p>
 *
 * <p><b>当前边界</b>：默认所有平台都兼容 OpenAI Chat Completions 协议、
 * 使用 {@code Authorization: Bearer} 鉴权。若某平台鉴权方式或接口路径不同，
 * 需扩展 {@link LlmProperties.Connection} 配置，不能假设所有平台一致。</p>
 */
@Component
@RequiredArgsConstructor
public class LlmClientFactory {

    private final LlmProperties properties;

    /**
     * 按连接 ID 缓存 WebClient：同一平台的多个模型复用一个 WebClient。
     */
    private final Map<String, WebClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 根据模型 ID 获取模型客户端。
     *
     * @param modelId 模型 ID；为空时使用默认模型
     * @return 解析完成的客户端信息（含 WebClient 与模型参数）
     * @throws IllegalArgumentException 模型 ID 不存在
     * @throws IllegalStateException    模型关联的平台连接不存在，或 baseUrl 为空
     */
    public ResolvedLlmClient getClient(String modelId) {
        String selectedModelId = (modelId == null || modelId.isBlank())
                ? properties.defaultModel()
                : modelId;

        // 1. 获取模型配置
        LlmProperties.Model model = properties.models().get(selectedModelId);
        if (model == null) {
            throw new IllegalArgumentException("不支持的模型: " + selectedModelId);
        }

        // 2. 获取平台连接配置
        String connectionId = model.connection();
        LlmProperties.Connection connection = properties.connections().get(connectionId);
        if (connection == null) {
            throw new IllegalStateException(
                    "模型[" + selectedModelId + "]关联的平台不存在: " + connectionId);
        }

        // 3. 获取或创建 WebClient（按连接 ID 缓存复用）
        WebClient webClient = clientCache.computeIfAbsent(connectionId, key -> createWebClient(connection));

        // 4. 返回客户端及模型参数
        return new ResolvedLlmClient(
                selectedModelId,
                model.modelName(),
                model.temperature(),
                model.maxTokens(),
                model.enableThinking(),
                connection.baseUrl(),
                webClient
        );
    }

    /**
     * 按平台连接配置创建 WebClient（base-url + Bearer 鉴权头）。
     *
     * @param connection 平台连接配置
     * @return 已配置鉴权的 {@link WebClient}
     */
    private WebClient createWebClient(LlmProperties.Connection connection) {
        if (connection.baseUrl() == null || connection.baseUrl().isBlank()) {
            throw new IllegalStateException("模型平台 baseUrl 不能为空");
        }
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(connection.baseUrl());
        if (connection.apiKey() != null && !connection.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey());
        }
        return builder.build();
    }

    /**
     * 已解析完成的模型客户端信息。
     *
     * @param modelId        模型 ID
     * @param modelName      平台侧模型名（放入请求体 model 字段）
     * @param temperature    温度（可为 null，走平台默认）
     * @param maxTokens      最大生成 token 数（可为 null，走平台默认）
     * @param enableThinking 是否启用思考阶段（平台扩展参数，可为 null = 不下发）
     * @param baseUrl        平台地址（便于排查/展示，WebClient 本身不暴露它）
     * @param webClient      对应平台的 WebClient
     */
    public record ResolvedLlmClient(
            String modelId,
            String modelName,
            Double temperature,
            Integer maxTokens,
            Boolean enableThinking,
            String baseUrl,
            WebClient webClient
    ) {
    }
}
