package com.example.jarvis.execution;

/**
 * 单个步骤的执行状态（第八阶段 8.3）。
 *
 * <p>状态机：</p>
 *
 * <pre>
 * PENDING ──→ RUNNING ──→ SUCCESS
 *    │            ↘ FAILED
 *    └──→ SKIPPED（依赖失败被跳过，从未启动）
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING}：等待执行。所有步骤的初始状态——前置依赖尚未全部完成，
 *       或尚未轮到调度</li>
 *   <li>{@link #RUNNING}：正在执行（当前单线程串行执行下瞬时存在，
 *       预留给后续并行执行时观察中间态）</li>
 *   <li>{@link #SUCCESS}：执行成功，{@code StepExecution.result} 携带步骤产出</li>
 *   <li>{@link #FAILED}：执行失败。失败原因/异常信息 8.6 失败处理阶段再扩展</li>
 *   <li>{@link #SKIPPED}：因依赖失败被跳过（外部原因，从未启动）。
 *       PENDING 直接 → SKIPPED，不经过 RUNNING（跳过的步骤没有执行动作，
 *       如实表达"从未启动"）。终态，且级联传播——依赖链上的 SKIPPED
 *       同样使其后续依赖不满足（SKIPPED ≠ SUCCESS）</li>
 * </ul>
 *
 * <p><b>SUCCESS / FAILED / SKIPPED 均为终态</b>，不可再转换。</p>
 */
public enum StepExecutionStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED
}
