package com.example.jarvis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 规划执行链路配置（前缀 {@code jarvis.plan}，8.6.5）。
 *
 * <p><b>model-id 的约定</b>：必须是 {@link LlmProperties} 中已注册的模型 ID
 * （{@code jarvis.llm.models} 的 key，如 {@code mxbc-qwen}），
 * <b>不是</b>模型供应商的原始模型名（如 {@code qwen3.8-27b}）。
 * {@code FinalAnswerService} 通过 {@code LlmClientFactory.getClient(modelId)}
 * 换取真实客户端——与策略判断（{@code jarvis.strategy.model-id}）、
 * 意图识别（{@code jarvis.intent.model-id}）同一套路。</p>
 *
 * <p><b>为什么独立配置</b>：最终答案整理模型与普通聊天模型、Planner 模型、
 * 策略判断模型解耦——未来切换更轻量/更强的模型只改 YAML，不动 Java 代码。
 * 留空时 FinalAnswerService 不传 modelId，由工厂回落默认模型。</p>
 *
 * @param modelId 最终答案整理使用的模型 ID（LlmProperties 已注册的模型 key；空则用默认模型）
 */
@ConfigurationProperties(prefix = "jarvis.plan")
public record PlanProperties(
        String modelId
) {
}
