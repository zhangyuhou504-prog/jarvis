package com.example.jarvis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 统一模型配置（前缀 {@code jarvis.llm}）。
 *
 * <p>把"模型名称"与"连接信息（地址 + Key）"解耦，支持多平台多模型扩展：</p>
 * <ul>
 *   <li>{@code connections}：连接信息，平台地址 + API Key（一个平台可配多个 Key）</li>
 *   <li>{@code models}：模型信息，模型名 + 所属连接 + 默认参数</li>
 *   <li>{@code defaultModel}：默认使用哪个模型配置</li>
 * </ul>
 *
 * <p>新增平台只需在 yaml 里加一个 connection + model，无需改代码。</p>
 */
@ConfigurationProperties(prefix = "jarvis.llm")
public record LlmProperties(
        String defaultModel,
        Map<String, Connection> connections,
        Map<String, Model> models
) {

    /**
     * 平台连接信息。
     *
     * @param baseUrl 平台 OpenAI 兼容接口根地址（如 {@code https://aigateway.mxbc.net/v1}）
     * @param apiKey  API Key（建议用环境变量注入）
     */
    public record Connection(
            String baseUrl,
            String apiKey
    ) {
    }

    /**
     * 模型配置。
     *
     * @param connection  所属连接标识（对应 {@code connections} 的 key）
     * @param modelName   平台侧模型名（如 {@code qwen3.8-27b}）
     * @param temperature 默认温度，可为 null（走平台默认）
     * @param maxTokens   最大生成 token 数，可为 null（走平台默认）
     */
    public record Model(
            String connection,
            String modelName,
            Double temperature,
            Integer maxTokens
    ) {
    }
}
