package com.example.jarvis.plan;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 计划校验器：对 Planner 生成的 {@link Plan} 做语义级合法性校验。
 *
 * <p><b>为什么需要它</b>：{@link Plan}/{@link PlanStep} 的紧凑构造器只保证
 * 结构性不变量（字段非空、集合不可变），而语义合法性需要看整个计划才能判断——
 * stepId 唯一性、依赖引用存在性、依赖无环。模型生成的 JSON 不保证满足这些，
 * 校验不通过时由上层决定重试或向用户报错。</p>
 *
 * <p><b>本质</b>：把计划看作有向依赖图，本校验器保证它是合法的 DAG
 * （有向无环图）——Planner 生成依赖图，PlanValidator 保证图合法，
 * PlanExecutor 按图执行。</p>
 *
 * <p><b>校验项与职责边界（第七阶段 7.4）</b>：</p>
 * <ol>
 *   <li>{@link #validateUniqueStepIds(Plan)}：stepId 唯一性（两个 step 都叫 step_1）</li>
 *   <li>{@link #validateDependenciesExist(Plan)}：dependsOn 指向的步骤是否存在
 *       （step_1 依赖 step_99）。<b>自依赖不属于本项职责</b>——stepId 存在，
 *       引用检查通过，它本质是长度为 1 的环，由 DFS 统一拦截，报错信息更准确</li>
 *   <li>{@link #validateNoCycles(Plan)}：循环依赖检测，DFS 三态标记（含自依赖）</li>
 * </ol>
 *
 * <p>统一入口 {@link #validate(Plan)} 按序执行三项检查——
 * 环检测依赖"引用都存在"的前提，顺序不能乱。</p>
 */
@Component
public class PlanValidator {

    /**
     * 校验计划合法性（总入口）：按序执行三项检查，任一失败立即抛出。
     *
     * <p>顺序有依赖关系：引用检查必须在环检测之前，
     * 否则环检测中 {@code stepMap.get(dep)} 为 null 会抛 NPE 而非友好异常。</p>
     *
     * @param plan 待校验的计划
     * @throws IllegalArgumentException 任一检查失败（消息指明具体原因）
     */
    public void validate(Plan plan) {
        validateUniqueStepIds(plan);
        validateDependenciesExist(plan);
        validateNoCycles(plan);
    }

    /**
     * 校验 stepId 唯一性。
     *
     * <p>利用 {@link Set#add} 的返回值判断重复：第一次添加返回 true，
     * 重复添加返回 false——无需双重循环，O(n) 完成检查。</p>
     *
     * @param plan 待校验的计划
     * @throws IllegalArgumentException 存在重复的 stepId
     */
    public void validateUniqueStepIds(Plan plan) {

        Set<String> stepIds = new HashSet<>();

        for (PlanStep step : plan.steps()) {
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException(
                        "存在重复的 stepId: " + step.stepId()
                );
            }
        }
    }

    /**
     * 校验依赖引用存在性：每个步骤 dependsOn 指向的 stepId 必须在计划中存在。
     *
     * <p>注意：自依赖（step_1 依赖 step_1）在此检查中<b>视为通过</b>——
     * 该 stepId 确实存在；它是长度为 1 的环，由 {@link #validateNoCycles(Plan)}
     * 以"循环依赖"的名义拦截，错误信息更准确。</p>
     *
     * @param plan 待校验的计划
     * @throws IllegalArgumentException 存在指向不存在步骤的依赖
     */
    public void validateDependenciesExist(Plan plan) {
        Set<String> stepIds = plan.steps().stream()
                .map(PlanStep::stepId)
                .collect(Collectors.toSet());

        for (PlanStep step : plan.steps()) {
            for (String dep : step.dependsOn()) {
                if (!stepIds.contains(dep)) {
                    throw new IllegalArgumentException(
                            "步骤[" + step.stepId() + "]依赖了不存在的步骤: " + dep
                    );
                }
            }
        }
    }

    /**
     * 校验依赖关系无环（含自依赖）。
     *
     * <p>构建 stepId → 步骤 索引后，对<b>每个</b>状态为 0 的步骤启动 DFS——
     * 不能只从第一个步骤开始，因为图中可能存在多个互不连通的依赖子图，
     * 环可能藏在后面某个子图里。三态标记表跨所有 DFS 共享：
     * 已完成的子树（状态 2）不会重复检查，整体 O(n)。</p>
     *
     * <p><b>前置条件</b>：依赖引用必须都存在（先过 {@link #validateDependenciesExist(Plan)}），
     * 否则 {@code stepMap.get(dep)} 为 null 会抛 NPE 而非友好的校验异常。</p>
     *
     * @param plan 待校验的计划
     * @throws IllegalArgumentException 存在循环依赖
     */
    public void validateNoCycles(Plan plan) {
        Map<String, PlanStep> stepMap = plan.steps().stream()
                .collect(Collectors.toMap(PlanStep::stepId, s -> s));

        Map<String, Integer> states = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            if (states.getOrDefault(step.stepId(), 0) == 0
                    && hasCycle(step.stepId(), stepMap, states)) {
                throw new IllegalArgumentException(
                        "存在循环依赖: " + step.stepId()
                );
            }
        }
    }

    /**
     * 从指定节点出发做 DFS，检测其依赖子图中是否存在环。
     *
     * <p><b>三态标记</b>（{@code states}：stepId → 状态）：</p>
     * <ul>
     *   <li>0（缺省）= 未访问</li>
     *   <li>1 = 正在访问（在当前 DFS 路径上）——再遇到它说明成环</li>
     *   <li>2 = 已完成（该节点子树已确认无环，无需重复检查）</li>
     * </ul>
     *
     * <p>本方法只负责"从某个节点开始查"，不负责遍历所有节点——
     * 那是外层 {@code validateNoCycles} 的职责（图中可能存在多个互不连通的子图）。</p>
     *
     * @param stepId  起始节点
     * @param stepMap stepId → 步骤 的索引
     * @param states  三态标记表（跨调用共享，保证 O(n) 且状态不互相污染）
     * @return true = 从该节点出发存在环
     */
    private boolean hasCycle(
            String stepId,
            Map<String, PlanStep> stepMap,
            Map<String, Integer> states
    ) {
        Integer state = states.getOrDefault(stepId, 0);

        // 正在访问：发现环
        if (state == 1) {
            return true;
        }

        // 已经访问完成：无需重复检查
        if (state == 2) {
            return false;
        }

        // 标记为正在访问
        states.put(stepId, 1);

        PlanStep step = stepMap.get(stepId);

        // 遍历所有依赖
        for (String dep : step.dependsOn()) {
            if (hasCycle(dep, stepMap, states)) {
                return true;
            }
        }

        // 所有依赖检查完成
        states.put(stepId, 2);

        return false;
    }
}
