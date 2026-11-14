package com.example.jarvis.strategy;

/**
 * 执行策略判断的输出结果（第八阶段 8.2）。
 *
 * <p>两份信息：怎么执行（{@code strategy}）+ 为什么这么选（{@code reason}）。
 * {@code reason} 不是装饰——排查误判、调整规则、后续做策略统计都靠它，
 * 没有原因的决策无法维护。</p>
 *
 * @param strategy 执行策略（非空）
 * @param reason   选择原因（非空，人可读，写入日志）
 */
public record StrategyResult(
        ExecutionStrategy strategy,
        String reason
) {
    public StrategyResult {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
    }
}
