package com.example.jarvis.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanScheduler 单元测试（第七阶段 7.5.4 验收）。
 *
 * <p>五个验收场景：普通线性依赖、多个无依赖步骤、一个步骤依赖多个步骤、
 * 存在循环依赖、空 Plan。所有用例都先过 {@link PlanValidator} 校验
 * （模拟真实链路：PlannerService 产出的 Plan 一定已校验），验证调度层
 * 在合法 DAG 上的拓扑正确性与环上的兜底拦截。</p>
 */
class PlanSchedulerTest {

    private final PlanValidator validator = new PlanValidator();
    private final PlanScheduler scheduler = new PlanScheduler();

    /**
     * 四步骤验证样例（贯穿 7.5 各小节的那张图）：
     * step_1、step_2 无依赖；step_3 依赖 step_1；step_4 依赖 step_2、step_3。
     */
    private Plan fourStepPlan() {
        return new Plan("示例目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of()),
                new PlanStep("step_3", "步骤三", List.of("step_1")),
                new PlanStep("step_4", "步骤四", List.of("step_2", "step_3"))
        ));
    }

    @Test
    void shouldSortLinearChain() {
        // 场景一：普通线性依赖 step_1 → step_2 → step_3，顺序唯一
        Plan plan = new Plan("线性链", List.of(
                new PlanStep("step_1", "第一步", List.of()),
                new PlanStep("step_2", "第二步", List.of("step_1")),
                new PlanStep("step_3", "第三步", List.of("step_2"))
        ));

        List<PlanStep> sorted = scheduler.topologicalSort(plan);

        assertEquals(List.of("step_1", "step_2", "step_3"),
                sorted.stream().map(PlanStep::stepId).toList());
    }

    @Test
    void shouldSortPlanWithMultipleIndependentSteps() {
        // 场景二：多个无依赖步骤，各自独立（两两无依赖关系的两个子图）
        Plan plan = new Plan("独立步骤", List.of(
                new PlanStep("step_1", "任务甲", List.of()),
                new PlanStep("step_2", "任务乙", List.of()),
                new PlanStep("step_3", "任务丙", List.of())
        ));

        List<PlanStep> sorted = scheduler.topologicalSort(plan);

        // 全部步骤都出队，只是相互间的顺序不作约束（都合法）
        assertEquals(3, sorted.size());
        // 集合维度断言：三个 stepId 都在结果里
        assertEquals(new java.util.HashSet<>(List.of("step_1", "step_2", "step_3")),
                new java.util.HashSet<>(sorted.stream().map(PlanStep::stepId).toList()));
    }

    @Test
    void shouldSortStepDependingOnMultipleSteps() {
        // 场景三：一个步骤依赖多个步骤（菱形 + 汇聚）：
        // step_1、step_2 独立开头；step_3 依赖 step_1；step_4 依赖 step_2、step_3
        Plan plan = fourStepPlan();
        validator.validate(plan);

        List<PlanStep> sorted = scheduler.topologicalSort(plan);
        List<String> order = sorted.stream().map(PlanStep::stepId).toList();

        // 全部 4 步都排出
        assertEquals(4, sorted.size());
        // 依赖语义：每个步骤一定排在其所有依赖之后
        assertTrue(order.indexOf("step_1") < order.indexOf("step_3"), "step_1 必须先于 step_3");
        assertTrue(order.indexOf("step_2") < order.indexOf("step_4"), "step_2 必须先于 step_4");
        assertTrue(order.indexOf("step_3") < order.indexOf("step_4"), "step_3 必须先于 step_4");
        // step_4 等到全部前置（step_2、step_3）安排完才出队——所有依赖满足才执行
    }

    @Test
    void shouldThrowOnCyclicPlan() {
        // 场景四：循环依赖 step_1 → step_2 → step_1（绕过 PlanValidator 直接调调度器，
        // 验证兜底防线：环上节点入度永远减不到 0，队列排空后 result 不完整）
        Plan cyclic = new Plan("循环", List.of(
                new PlanStep("step_1", "步骤一", List.of("step_2")),
                new PlanStep("step_2", "步骤二", List.of("step_1"))
        ));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> scheduler.topologicalSort(cyclic));

        assertTrue(ex.getMessage().contains("循环依赖"));
        assertTrue(ex.getMessage().contains("0/2"), "报错应说明已排序 0/2 步");
    }

    @Test
    void shouldSortEmptyPlan() {
        // 场景五：空 Plan（steps 为空）——初始队列为空，循环体不执行，
        // result.size()=0 == steps.size()=0，合法返回空列表
        Plan plan = new Plan("空计划", List.of());

        List<PlanStep> sorted = scheduler.topologicalSort(plan);

        assertTrue(sorted.isEmpty());
    }
}
