package com.example.jarvis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行策略判断配置（前缀 {@code jarvis.strategy}，第八阶段 8.2.6）。
 *
 * <p><b>model-id 的约定</b>：必须是 {@link LlmProperties} 中已注册的模型 ID
 * （{@code jarvis.llm.models} 的 key，如 {@code mxbc-qwen}），
 * <b>不是</b>模型供应商的原始模型名（如 {@code qwen3.8-27b}）。
 * {@code StrategyClassifier} 通过 {@code LlmClientFactory.getClient(modelId)}
 * 换取真实客户端，跟意图识别（{@code jarvis.intent.model-id}）同一套路。</p>
 *
 * <p><b>为什么独立配置</b>：策略判断模型与普通聊天模型、Planner 模型解耦——
 * 未来切换更轻量/更快的模型只改 YAML，不动 Java 代码。</p>
 *
 * @param modelId 策略判断使用的模型 ID（LlmProperties 已注册的模型 key）
 */
@ConfigurationProperties(prefix = "jarvis.strategy")
public record StrategyProperties(
        String modelId
) {
}
