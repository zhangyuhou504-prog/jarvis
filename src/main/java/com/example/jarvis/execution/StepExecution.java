package com.example.jarvis.execution;

/**
 * 单个步骤的执行信息（第八阶段 8.3）。
 *
 * <p>记录某个 PlanStep 在执行过程中的当前状态与产出。
 * 由 {@code ExecutionContext} 按步骤粒度持有（{@code Map<stepId, StepExecution>}），
 * 随执行推进不断更新。</p>
 *
 * <p><b>result 为什么是 {@link Object} 而非 String</b>：不同步骤的产出类型不同
 * （计算结果是数值、查询结果是文本、判断结果是布尔），第一版不做类型收窄，
 * 由步骤执行方决定产出物；需要落盘/展示时再序列化。</p>
 *
 * <p><b>result 与 status 的关系</b>：{@code PENDING} / {@code RUNNING} 时为 null
 * （尚无产出）；{@code SUCCESS} 时为步骤输出（可为 null——有些步骤本身无产出，
 * 如"澄清需求"类步骤）；{@code FAILED} 时通常为 null（失败详情 8.6 扩展
 * 异常字段后承载，不混进 result）。</p>
 *
 * <p><b>为什么用不可变 record</b>：状态更新通过"创建新实例替换"而非原地修改——
 * 配合 ExecutionContext 统一管理状态转换，避免执行中途被外部引用意外篡改。
 * （8.6 失败处理时再按需扩展 error、耗时等字段。）</p>
 *
 * @param status 执行状态（非空）
 * @param result 步骤产出（可空，语义见上）
 */
public record StepExecution(
        StepExecutionStatus status,
        Object result
) {
    public StepExecution {
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
    }

    /**
     * 便捷工厂：构建 PENDING 初始状态（无产出）。
     *
     * @return status=PENDING, result=null 的执行信息
     */
    public static StepExecution pending() {
        return new StepExecution(StepExecutionStatus.PENDING, null);
    }
}
