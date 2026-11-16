package com.example.jarvis.execution;

/**
 * 计划执行结果汇总（8.6.2）：一次执行结束后对全部步骤终态的整体结论。
 *
 * <p>调用方不必逐步遍历 {@code stepExecutions}，直接通过本汇总回答——</p>
 * <ul>
 *   <li>总共执行了几个步骤？（{@code totalSteps}）</li>
 *   <li>成功几个？（{@code successCount}）</li>
 *   <li>失败几个？（{@code failedCount}）</li>
 *   <li>跳过几个？（{@code skippedCount}）</li>
 *   <li>整个计划是否成功？（{@code status}：SUCCESS / PARTIAL_SUCCESS / FAILED）</li>
 * </ul>
 *
 * <p><b>统计的是最终状态，不是执行次数</b>：某步骤失败重试 3 次后成功，
 * 计入 successCount 一次——汇总口径与步骤终态一一对应。</p>
 *
 * <p><b>如何获得</b>：{@code ExecutionContext.summarize()} 在执行结束后调用，
 * 读取全部步骤的最终状态统计生成（不可变快照，后续状态变化不影响已生成的汇总）。</p>
 *
 * <p>与执行详情的分工：需要"哪一步为什么失败"看
 * {@code ExecutionContext.getStepExecution(stepId)}（详情），
 * 需要"整体怎么样"看本对象（结论）。</p>
 *
 * @param status       整体执行状态
 * @param totalSteps   总步骤数
 * @param successCount 成功步骤数
 * @param failedCount  失败步骤数
 * @param skippedCount 跳过步骤数
 */
public record PlanExecutionSummary(
        PlanExecutionStatus status,
        int totalSteps,
        int successCount,
        int failedCount,
        int skippedCount
) {
}
