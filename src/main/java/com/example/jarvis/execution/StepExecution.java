package com.example.jarvis.execution;

/**
 * 单个步骤的执行信息（第八阶段 8.3 + 8.6.1 失败原因）。
 *
 * <p>记录某个 PlanStep 在执行过程中的当前状态、产出与失败/跳过原因。
 * 由 {@code ExecutionContext} 按步骤粒度持有（{@code Map<stepId, StepExecution>}），
 * 随执行推进不断更新。</p>
 *
 * <p><b>result 为什么是 {@link Object} 而非 String</b>：不同步骤的产出类型不同
 * （计算结果是数值、查询结果是文本、判断结果是布尔），第一版不做类型收窄，
 * 由步骤执行方决定产出物；需要落盘/展示时再序列化。</p>
 *
 * <p><b>result / error 与 status 的关系（8.6.1 约定）</b>：</p>
 * <table border="1">
 *   <caption>状态 → 字段取值</caption>
 *   <tr><th>状态</th><th>result</th><th>error</th></tr>
 *   <tr><td>SUCCESS</td><td>步骤产出（可为 null——无产出步骤）</td><td>null</td></tr>
 *   <tr><td>FAILED</td><td>null</td><td>失败原因（异常信息 / 闭环失败说明）</td></tr>
 *   <tr><td>SKIPPED</td><td>null</td><td>跳过原因（依赖未成功等）</td></tr>
 *   <tr><td>PENDING / RUNNING</td><td>null</td><td>null</td></tr>
 * </table>
 *
 * <p>error 复用于 FAILED（执行失败原因）与 SKIPPED（未执行原因）——
 * 两者对用户都是"这步为什么没有产出"，语义同源。</p>
 *
 * <p><b>敏感信息注意</b>：异常信息可能携带工具参数等敏感内容，当前先原样
 * 保存在执行上下文中供诊断；后续对外展示时再做脱敏。</p>
 *
 * <p><b>为什么用不可变 record</b>：状态更新通过"创建新实例替换"而非原地修改——
 * 配合 ExecutionContext 统一管理状态转换，避免执行中途被外部引用意外篡改。</p>
 *
 * @param status 执行状态（非空）
 * @param result 步骤产出（可空，语义见上）
 * @param error  失败/跳过原因（FAILED/SKIPPED 时非空，其余状态 null）
 */
public record StepExecution(
        StepExecutionStatus status,
        Object result,
        String error
) {
    public StepExecution {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
    }

    /**
     * 便捷工厂：构建 PENDING 初始状态（无产出、无失败信息）。
     *
     * @return status=PENDING, result=null, error=null 的执行信息
     */
    public static StepExecution pending() {
        return new StepExecution(StepExecutionStatus.PENDING, null, null);
    }
}
