package com.example.jarvis.execution;

import com.example.jarvis.plan.PlanResult;
import com.example.jarvis.plan.PlanStep;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行上下文（第八阶段 8.3.2）：统一管理一次计划执行的全程状态。
 *
 * <p><b>职责（只有两件事）</b>：</p>
 * <ol>
 *   <li>持有当前要执行的计划 {@link PlanResult}——同时提供原始 Plan
 *       （任务拆解结果）与 executionOrder（拓扑排序后的执行顺序）</li>
 *   <li>持有每个步骤的执行信息 {@code Map<stepId, StepExecution>}——
 *       执行器通过 stepId 快速定位某步的状态与产出</li>
 * </ol>
 *
 * <p><b>构造即初始化</b>：构造时遍历 executionOrder，把每个步骤初始化为
 * {@link StepExecutionStatus#PENDING}、result=null——上下文创建即代表
 * "计划已就绪、全部步骤待执行"，不存在"上下文存在但某步骤没有状态"的中间态。</p>
 *
 * <p><b>为什么 Map 用 LinkedHashMap</b>：保持 executionOrder 的顺序——
 * 遍历执行信息（日志、进度展示、最终报告）时按执行顺序输出，
 * 而不是 HashMap 的乱序。</p>
 *
 * <p><b>与 Plan 数据结构的边界</b>：Plan/PlanStep 只表达"要做什么"
 * （纯规划，不含执行状态）；本类是执行侧状态容器，二者通过 stepId 关联。
 * 执行状态永远不回流进 Plan（规划与执行解耦，7.1 确立的原则）。</p>
 */
public class ExecutionContext {

    /**
     * 当前要执行的计划（原始 Plan + 拓扑执行顺序）。
     */
    private final PlanResult planResult;

    /**
     * 每个步骤的执行信息（stepId → 执行状态与产出）。
     * LinkedHashMap 保持执行顺序，构造时全部初始化为 PENDING。
     */
    private final Map<String, StepExecution> stepExecutions;

    /**
     * 构造执行上下文：所有步骤初始化为 PENDING（result=null）。
     *
     * @param planResult 待执行的计划（非空，须已通过 PlanValidator 校验）
     */
    public ExecutionContext(PlanResult planResult) {
        if (planResult == null) {
            throw new IllegalArgumentException("planResult 不能为空");
        }
        this.planResult = planResult;
        this.stepExecutions = new LinkedHashMap<>();

        // 遍历 executionOrder（拓扑顺序），每个步骤初始化为 PENDING
        for (PlanStep step : planResult.executionOrder()) {
            this.stepExecutions.put(step.stepId(), StepExecution.pending());
        }
    }

    /**
     * 获取当前执行的计划。
     */
    public PlanResult getPlanResult() {
        return planResult;
    }

    /**
     * 查询某个步骤的执行信息。
     *
     * @param stepId 步骤标识
     * @return 执行信息；stepId 不存在于计划中时返回 null
     */
    public StepExecution getStepExecution(String stepId) {
        return stepExecutions.get(stepId);
    }

    /**
     * 获取全部执行信息（按执行顺序）。
     *
     * <p>返回内部 Map 的只读视图——执行器只能通过 {@link #markRunning} /
     * {@link #markSuccess} / {@link #markFailed} 更新状态（统一状态转换入口，
     * 防止绕过状态机直接改 Map），读访问自由。</p>
     */
    public Map<String, StepExecution> getStepExecutions() {
        return java.util.Collections.unmodifiableMap(stepExecutions);
    }

    // ==================== 状态更新（统一入口） ====================
    // StepExecution 是不可变 record：状态转换 = 创建新实例替换 Map 中的旧值，
    // 绝不原地修改。非法转换抛 IllegalStateException，防止状态机被破坏。

    /**
     * 标记步骤开始执行：PENDING → RUNNING。
     *
     * @param stepId 步骤标识
     * @throws IllegalArgumentException stepId 不在计划中
     * @throws IllegalStateException    当前状态不是 PENDING（如重复启动、已完成步骤再启动）
     */
    public void markRunning(String stepId) {
        StepExecution current = requireStep(stepId);
        if (current.status() != StepExecutionStatus.PENDING) {
            throw new IllegalStateException(
                    "非法状态转换: " + stepId + " " + current.status() + " → RUNNING（仅 PENDING 可启动）");
        }
        stepExecutions.put(stepId, new StepExecution(StepExecutionStatus.RUNNING, null));
    }

    /**
     * 标记步骤执行成功：RUNNING → SUCCESS，保存步骤产出。
     *
     * @param stepId 步骤标识
     * @param result 步骤产出（可为 null——某些步骤本身无产出）
     * @throws IllegalArgumentException stepId 不在计划中
     * @throws IllegalStateException    当前状态不是 RUNNING（PENDING 直接 SUCCESS 跳过了执行过程）
     */
    public void markSuccess(String stepId, Object result) {
        StepExecution current = requireStep(stepId);
        if (current.status() != StepExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "非法状态转换: " + stepId + " " + current.status() + " → SUCCESS（仅 RUNNING 可完成）");
        }
        stepExecutions.put(stepId, new StepExecution(StepExecutionStatus.SUCCESS, result));
    }

    /**
     * 标记步骤执行失败：RUNNING → FAILED。
     *
     * <p>失败详情（异常信息、重试计数等）8.6 失败处理阶段再扩展，当前只记录状态。</p>
     *
     * @param stepId 步骤标识
     * @throws IllegalArgumentException stepId 不在计划中
     * @throws IllegalStateException    当前状态不是 RUNNING
     */
    public void markFailed(String stepId) {
        StepExecution current = requireStep(stepId);
        if (current.status() != StepExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "非法状态转换: " + stepId + " " + current.status() + " → FAILED（仅 RUNNING 可失败）");
        }
        stepExecutions.put(stepId, new StepExecution(StepExecutionStatus.FAILED, null));
    }

    /**
     * 标记步骤被跳过：PENDING → SKIPPED（依赖失败，级联跳过）。
     *
     * <p>与 FAILED 的区别：FAILED 是"执行了但失败"，SKIPPED 是"因依赖不满足
     * 从未启动"。PENDING 直接 → SKIPPED 不经过 RUNNING——跳过的步骤没有
     * 执行动作，如实表达"从未启动"。终态。</p>
     *
     * @param stepId 步骤标识
     * @throws IllegalArgumentException stepId 不在计划中
     * @throws IllegalStateException    当前状态不是 PENDING（已启动/已终态的步骤不能跳过）
     */
    public void markSkipped(String stepId) {
        StepExecution current = requireStep(stepId);
        if (current.status() != StepExecutionStatus.PENDING) {
            throw new IllegalStateException(
                    "非法状态转换: " + stepId + " " + current.status() + " → SKIPPED（仅 PENDING 可跳过）");
        }
        stepExecutions.put(stepId, new StepExecution(StepExecutionStatus.SKIPPED, null));
    }

    /**
     * 查询某步骤的前置依赖是否全部执行成功。
     *
     * <p>执行器调度时用它判断一个步骤是否可以启动——依赖未全部 SUCCESS
     * 则不能开始（依赖中有 PENDING/RUNNING 表示还没轮到；有 FAILED 表示
     * 该步骤在当前失败处理策略下不可执行）。</p>
     *
     * @param stepId 步骤标识
     * @return true = 全部依赖已 SUCCESS（无依赖的步骤恒为 true）
     * @throws IllegalArgumentException stepId 不在计划中
     */
    public boolean areDependenciesSatisfied(String stepId) {
        requireStep(stepId);
        return planResult.plan().steps().stream()
                .filter(step -> step.stepId().equals(stepId))
                .findFirst()
                .orElseThrow()
                .dependsOn()
                .stream()
                .allMatch(dep -> {
                    StepExecution depExec = stepExecutions.get(dep);
                    return depExec != null && depExec.status() == StepExecutionStatus.SUCCESS;
                });
    }

    /**
     * 校验 stepId 存在于当前计划中并返回其执行信息。
     *
     * @throws IllegalArgumentException stepId 不在计划中
     */
    private StepExecution requireStep(String stepId) {
        StepExecution exec = stepExecutions.get(stepId);
        if (exec == null) {
            throw new IllegalArgumentException("步骤不存在于当前计划中: " + stepId);
        }
        return exec;
    }
}
