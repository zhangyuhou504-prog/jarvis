package com.example.jarvis.plan;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规划结果：一次完整规划的产出（第七阶段 7.6）。
 *
 * <p>同时持有两份数据：</p>
 * <ul>
 *   <li>{@link #plan}：模型原始拆解出的计划（Planner 产出，已过校验）</li>
 *   <li>{@link #executionOrder}：经拓扑排序后的合法执行顺序（PlanScheduler 产出）</li>
 * </ul>
 *
 * <p><b>为什么不把 executionOrder 塞进 Plan</b>：原始计划与调度结果是两个
 * 不同概念——Plan 是"模型对目标的理解与拆解"，executionOrder 是"调度器
 * 依据依赖图算出的执行时序"。分开保存，Plan 保持纯净（只表达要做什么与
 * 步骤间关系），调度产物独立演进（后续并行批次、优先级等变化不影响 Plan）。</p>
 *
 * <p><b>不变量</b>：executionOrder 与 plan.steps() 是<b>同一批步骤的不同排列</b>
 * ——元素相同（无缺失、无重复），只是顺序不同。compact 构造器校验非空与
 * 集合一致性，非法对象造不出来。</p>
 */
public record PlanResult(
        Plan plan,
        List<PlanStep> executionOrder
) {
    public PlanResult {
        if (plan == null) {
            throw new IllegalArgumentException("plan 不能为空");
        }
        if (executionOrder == null) {
            throw new IllegalArgumentException("executionOrder 不能为空");
        }
        // 同一批步骤的不同排列：数量一致 + stepId 集合一致（防缺失/重复/错位）
        if (executionOrder.size() != plan.steps().size()) {
            throw new IllegalArgumentException(
                    "executionOrder 与 plan.steps 数量不一致: "
                            + executionOrder.size() + "/" + plan.steps().size());
        }
        Set<String> planIds = plan.steps().stream()
                .map(PlanStep::stepId)
                .collect(Collectors.toSet());
        Set<String> orderIds = executionOrder.stream()
                .map(PlanStep::stepId)
                .collect(Collectors.toSet());
        if (!planIds.equals(orderIds)) {
            throw new IllegalArgumentException("executionOrder 与 plan.steps 的 stepId 集合不一致");
        }
        executionOrder = List.copyOf(executionOrder);
    }
}
