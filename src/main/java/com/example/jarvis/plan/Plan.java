package com.example.jarvis.plan;

import java.util.List;

/**
 * 任务计划：Planner 对用户复杂目标的拆解结果。
 *
 * <p>例如用户说"帮我计算 128 × 256，然后把结果乘以 2"，计划为
 * goal="计算最终结果"，steps=[计算 128×256 → 将结果乘以 2]。</p>
 *
 * <p><b>设计边界（第七阶段 7.1）</b>：</p>
 * <ul>
 *   <li>只表达"要做什么、步骤间什么关系"，不含执行状态
 *       （PENDING/RUNNING/SUCCESS/FAILED 属于 PlanExecutor 阶段，届时再设计）</li>
 *   <li>不含 toolName/arguments——规划与工具执行解耦：Planner 负责理解目标、
 *       拆解步骤，工具的选择与调用由 Tool Calling 链路负责，
 *       后续演进时再让计划携带可执行细节</li>
 * </ul>
 *
 * <p><b>不变量</b>：goal 非空；steps 不可变（null 归一为空列表）。
 * 语义级校验（步骤非空、依赖指向存在的 stepId、无环）由计划解析校验环节负责。</p>
 *
 * @param goal  用户希望完成的最终目标
 * @param steps 任务步骤集合（不可变）
 */
public record Plan(

        String goal,

        List<PlanStep> steps
) {

    public Plan {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
