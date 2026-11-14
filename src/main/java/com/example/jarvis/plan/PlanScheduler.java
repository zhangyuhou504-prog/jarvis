package com.example.jarvis.plan;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 计划调度器：根据 {@link Plan} 的依赖关系，生成合法的执行顺序（拓扑排序）。
 *
 * <p><b>职责边界（第七阶段 7.5.1）</b>：只做一件事——
 * 把 DAG 形态的 Plan 排成一个"依赖在前、被依赖在后"的线性步骤序列。
 * 不负责执行步骤、不维护执行状态（那是 PlanExecutor 的职责）。</p>
 *
 * <p><b>算法</b>：Kahn（广度优先）拓扑排序——
 * 反复取出"入度为 0"（所有前置依赖都已完成）的步骤，
 * 每取出一个，就把它指向的后续步骤的入度减一，产生新的入度 0 步骤。</p>
 *
 * <p><b>正向依赖 vs 反向依赖</b>：{@link PlanStep#dependsOn()} 表达的是正向依赖
 * （step_3 dependsOn step_1 = "我依赖谁"），但 Kahn 算法推进时需要回答的是
 * 反向问题——"step_1 完成后，哪些步骤可以解除依赖"（step_1 → [step_3]）。
 * 所以排序前先构建反向邻接表，这也是需要两个 Map 的原因：</p>
 *
 * <table border="1">
 *   <caption>核心数据结构</caption>
 *   <tr><th>数据结构</th><th>用途</th></tr>
 *   <tr><td>Map&lt;String, Integer&gt;</td><td>每个步骤的入度（还剩多少个前置依赖未完成）</td></tr>
 *   <tr><td>Map&lt;String, List&lt;String&gt;&gt;</td><td>反向依赖关系（step_1 → [step_3]）</td></tr>
 *   <tr><td>Queue&lt;String&gt;</td><td>入度为 0、随时可执行的步骤</td></tr>
 *   <tr><td>List&lt;PlanStep&gt;</td><td>最终排序结果</td></tr>
 * </table>
 *
 * <p><b>前置条件</b>：入参 Plan 必须已通过 {@link PlanValidator} 校验
 * （stepId 唯一、依赖引用存在、无环）。环在调度层表现为"队列提前排空、
 * 结果不完整"，但语义校验应在上游拦截，本类不做重复校验。</p>
 *
 * <p><b>Spring 注册</b>：{@code @Component}——被
 * {@code PlanOrchestrationService} 构造器注入；无状态、线程安全
 * （topologicalSort 所有状态都在方法栈内）。</p>
 */
@Slf4j
@Component
public class PlanScheduler {

    /**
     * 对计划做拓扑排序，返回合法执行顺序的步骤列表。
     *
     * <p>Kahn 算法：入度为 0 的步骤先执行；每完成一个步骤，
     * 通过反向依赖表找到所有依赖它的步骤并将其入度减一，
     * 入度归零的步骤进入待执行队列。</p>
     *
     * <p>无依赖关系的步骤（入度恒为 0）按计划中的原始顺序自然先出队——
     * 后续如果要支持并行执行，同批出队的步骤就是可并行的一组。</p>
     *
     * @param plan 待调度的计划（须已通过 PlanValidator 校验，保证是合法 DAG）
     * @return 拓扑有序的步骤列表（依赖一定排在被依赖之前）
     */
    public List<PlanStep> topologicalSort(Plan plan) {

        // ---------- 7.5.2 构建入度表与反向依赖表 ----------

        // 1) 入度表：初始入度 = dependsOn 的数量（尚未解除的前置依赖个数）
        //    例：step_4 dependsOn [step_2, step_3] → 初始入度 2
        Map<String, Integer> inDegree = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            inDegree.put(step.stepId(), step.dependsOn().size());
        }

        // 2) 反向依赖表：先为每个步骤登记一个空列表，
        //    保证"没有被任何步骤依赖"的步骤（如示例中的 step_4）也有 key 存在
        Map<String, List<String>> dependents = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            dependents.put(step.stepId(), new ArrayList<>());
        }

        // 3) 反向依赖表：遍历正向依赖，翻转登记
        //    正向 dependsOn  —— step_3 dependsOn step_1（"我依赖谁"）
        //    反向 dependents —— step_1 → [step_3]（"我完成后解锁谁"）
        for (PlanStep step : plan.steps()) {
            for (String dep : step.dependsOn()) {
                dependents.get(dep).add(step.stepId());
            }
        }

        // ---------- 7.5.3 初始化队列 ----------

        // 4) stepMap：stepId → PlanStep 索引。
        //    为什么需要它：队列里流转的是 stepId（String），而方法返回的是
        //    List<PlanStep>，出队后要靠它找回完整的步骤对象
        Map<String, PlanStep> stepMap = new HashMap<>();
        for (PlanStep step : plan.steps()) {
            stepMap.put(step.stepId(), step);
        }

        // 5) 初始化就绪队列：所有入度为 0 的步骤（无任何前置依赖，第一批可执行）
        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        // 6) 排序结果：按拓扑顺序收集 PlanStep（依赖一定排在被依赖之前）
        List<PlanStep> result = new ArrayList<>();

        log.info("队列初始化完成: 初始就绪步骤={}, 结果集大小={}", queue, result.size());

        // ---------- 7.5.4 Kahn 主循环：出队 → 入结果 → 解除依赖 ----------

        // 队列非空说明还有可执行步骤：逐个取出，安排进拓扑顺序，
        // 并通过反向依赖表把它"锁住"的后续步骤入度减 1
        while (!queue.isEmpty()) {
            String current = queue.poll();

            // 1) 当前步骤的所有前置依赖均已完成（入度 0 才会出队），
            //    安排进拓扑顺序结果
            result.add(stepMap.get(current));

            // 2) 找到依赖当前步骤的所有后续步骤（"我完成后谁被解锁"）
            for (String dependent : dependents.get(current)) {

                // 3) 入度减 1：该后续步骤解除一个前置依赖。
                //    例：step_4 dependsOn [step_2, step_3]，初始入度 2；
                //    step_2 完成后变 1，step_3 完成后才变 0
                int degree = inDegree.get(dependent) - 1;
                inDegree.put(dependent, degree);

                // 4) 入度归零 = 所有前置依赖都已安排 → 加入就绪队列。
                //    注意：不是"有一个依赖完成"就能入队，而是全部完成
                if (degree == 0) {
                    queue.offer(dependent);
                }
            }
        }

        // ---------- 环检测兜底 ----------

        // 合法 DAG 的所有步骤最终都会出队进 result；存在环时，环上节点
        // 的入度永远减不到 0，队列提前排空，result 少于计划步骤总数。
        // 正常链路 Plan 已过 PlanValidator 校验不会带环到这里，这是兜底防线
        if (result.size() != plan.steps().size()) {
            throw new IllegalArgumentException(
                    "Plan 存在循环依赖，无法完成拓扑排序: 已排序 "
                            + result.size() + "/" + plan.steps().size() + " 步");
        }

        log.info("拓扑排序完成: 目标={}, 执行顺序={}",
                plan.goal(),
                result.stream().map(PlanStep::stepId).toList());

        return result;
    }
}
