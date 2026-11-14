package com.example.jarvis.plan;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规划协调服务（第七阶段 7.6）：串联完整规划流程，只协调、不做具体工作。
 *
 * <p><b>职责分离，流程协调</b>——本类自己不生成计划、不校验、不排序，
 * 只负责把三个模块按正确顺序串起来：</p>
 *
 * <table border="1">
 *   <caption>协作模块与职责</caption>
 *   <tr><th>模块</th><th>职责</th></tr>
 *   <tr><td>{@link PlannerService}</td><td>让模型生成计划（内含解析 + 校验 + 失败重试，7.4）</td></tr>
 *   <tr><td>{@link PlanValidator}</td><td>检查计划语义合法性（stepId 唯一 → 引用存在 → 无环）</td></tr>
 *   <tr><td>{@link PlanScheduler}</td><td>计算拓扑执行顺序（Kahn 算法）</td></tr>
 *   <tr><td>本类</td><td>按序调用三者，组装 {@link PlanResult} 返回</td></tr>
 * </table>
 *
 * <p><b>为什么需要这个类</b>：后续 Agent 执行阶段（PlanExecutor）直接使用
 * 本协调服务即可，不必自己重复编排三个模块——规划流程的调用顺序只写一处。</p>
 *
 * <p><b>关于二次校验</b>：{@link PlannerService#createPlan} 在 7.4 已内嵌
 * PlanValidator（重试机制依赖校验结果做修正反馈），此处再校验一次属
 * 防御性复核——协调层只放行自己验证过的 DAG 给调度器，代价 O(n) 可忽略。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PlanOrchestrationService {

    private final PlannerService plannerService;
    private final PlanValidator planValidator;
    private final PlanScheduler planScheduler;

    /**
     * 完整规划流程：生成计划 → 复核合法性 → 计算执行顺序 → 组装结果。
     *
     * @param goal 用户希望完成的最终目标（自然语言）
     * @return 规划结果（原始计划 + 拓扑有序的执行顺序，同一批步骤的不同排列）
     * @throws IllegalStateException    模型输出重试耗尽仍无法解析
     * @throws IllegalArgumentException 计划语义不合法（重复 stepId / 引用缺失 / 循环依赖）
     */
    public PlanResult createPlan(String goal) {

        // 1. 生成计划（PlannerService 内部：调模型 → 解析 → 校验 → 失败带原因重试）
        Plan plan = plannerService.createPlan(goal);

        // 2. 复核计划合法性（统一入口，内部按序执行三项检查；
        //    协调层防线：只放行验证过的 DAG 给调度器）
        planValidator.validate(plan);

        // 3. 生成拓扑执行顺序（依赖在前、被依赖在后；无依赖步骤可并行）
        List<PlanStep> executionOrder = planScheduler.topologicalSort(plan);

        // 4. 组装规划结果（PlanResult 校验两份步骤为同一批不同排列）
        PlanResult result = new PlanResult(plan, executionOrder);

        log.info("规划流程完成: goal={}, 步骤数={}, 执行顺序={}",
                goal, plan.steps().size(),
                executionOrder.stream().map(PlanStep::stepId).toList());
        return result;
    }
}
