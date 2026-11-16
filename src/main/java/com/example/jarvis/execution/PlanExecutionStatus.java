package com.example.jarvis.execution;

/**
 * 计划整体执行状态（8.6.2）：对一次执行结束后所有步骤终态的汇总结论。
 *
 * <p>判定规则（由 {@code ExecutionContext.summarize()} 计算）：</p>
 * <table border="1">
 *   <caption>整体状态 → 判断规则</caption>
 *   <tr><th>整体状态</th><th>判断规则</th></tr>
 *   <tr><td>{@link #SUCCESS}</td><td>所有步骤均成功</td></tr>
 *   <tr><td>{@link #PARTIAL_SUCCESS}</td><td>有成功步骤，同时存在失败或跳过步骤</td></tr>
 *   <tr><td>{@link #FAILED}</td><td>没有任何成功步骤</td></tr>
 * </table>
 *
 * <p>与 {@link StepExecutionStatus}（单步状态）的边界：单步状态描述
 * "这一步怎么样"，本枚举描述"整个计划怎么样"——调用方拿到汇总即可决策
 * （向用户报成功 / 解释部分失败 / 报整体失败），不必逐步遍历。</p>
 */
public enum PlanExecutionStatus {

    /** 所有步骤均成功——计划完整达成。 */
    SUCCESS,

    /** 有成功步骤，同时存在失败或跳过步骤——计划部分达成，需向用户解释未完成部分。 */
    PARTIAL_SUCCESS,

    /** 没有任何成功步骤——计划整体失败。 */
    FAILED
}
