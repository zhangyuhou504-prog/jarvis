package com.example.jarvis.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    private final PlanValidator validator = new PlanValidator();

    @Test
    void shouldPassWhenStepIdsAreUnique() {
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of("step_1"))
        ));

        assertDoesNotThrow(() -> validator.validateUniqueStepIds(plan));
    }

    @Test
    void shouldRejectDuplicateStepIds() {
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_1", "步骤二", List.of("step_1"))
        ));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateUniqueStepIds(plan));
        assertTrue(ex.getMessage().contains("step_1"));
    }

    @Test
    void shouldPassForEmptySteps() {
        Plan plan = new Plan("目标", List.of());
        assertDoesNotThrow(() -> validator.validateUniqueStepIds(plan));
    }

    // ---- validateDependenciesExist ----

    @Test
    void shouldPassWhenDependencyExists() {
        // step_2 依赖 step_1，且 step_1 存在
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of("step_1"))
        ));

        assertDoesNotThrow(() -> validator.validateDependenciesExist(plan));
    }

    @Test
    void shouldRejectDependencyOnMissingStep() {
        // step_2 依赖 step_99，且 step_99 不存在
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of("step_99"))
        ));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateDependenciesExist(plan));
        assertTrue(ex.getMessage().contains("step_99"));
    }

    @Test
    void shouldPassSelfDependencyAtReferenceCheck() {
        // step_1 依赖 step_1：引用检查通过（stepId 存在），
        // 它是长度为 1 的环，由后续 validateNoCycles 拦截
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of("step_1"))
        ));

        assertDoesNotThrow(() -> validator.validateDependenciesExist(plan));
    }

    @Test
    void shouldPassWhenMultipleDependenciesExist() {
        // step_3 同时依赖 step_1、step_2，且两者存在
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of()),
                new PlanStep("step_3", "步骤三", List.of("step_1", "step_2"))
        ));

        assertDoesNotThrow(() -> validator.validateDependenciesExist(plan));
    }

    // ---- validateNoCycles ----

    @Test
    void shouldPassForAcyclicPlan() {
        // 线性链 + 菱形依赖，均无环
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of("step_1")),
                new PlanStep("step_3", "步骤三", List.of("step_1")),
                new PlanStep("step_4", "步骤四", List.of("step_2", "step_3"))
        ));

        assertDoesNotThrow(() -> validator.validateNoCycles(plan));
    }

    @Test
    void shouldRejectSelfDependency() {
        // 自依赖：长度为 1 的环
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of("step_1"))
        ));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validateNoCycles(plan));
    }

    @Test
    void shouldRejectTwoNodeCycle() {
        // step_1 ↔ step_2 互依赖
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of("step_2")),
                new PlanStep("step_2", "步骤二", List.of("step_1"))
        ));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validateNoCycles(plan));
    }

    @Test
    void shouldRejectCycleInDisconnectedSubgraph() {
        // 关键场景：step_1 独立无环，环藏在后面的 step_3 ↔ step_4 子图里。
        // 如果只从 step_1 开始 DFS，会漏掉这个环。
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_3", "步骤三", List.of("step_4")),
                new PlanStep("step_4", "步骤四", List.of("step_3"))
        ));

        assertThrows(IllegalArgumentException.class,
                () -> validator.validateNoCycles(plan));
    }

    @Test
    void shouldPassForTwoDisconnectedAcyclicSubgraphs() {
        // step_1 → step_2 与 step_3 → step_4 两个互不连通的子图，均无环。
        // 外层 for 循环保证第二个子图也被检查（且检查后通过）。
        Plan plan = new Plan("目标", List.of(
                new PlanStep("step_1", "步骤一", List.of("step_2")),
                new PlanStep("step_2", "步骤二", List.of()),
                new PlanStep("step_3", "步骤三", List.of("step_4")),
                new PlanStep("step_4", "步骤四", List.of())
        ));

        assertDoesNotThrow(() -> validator.validateNoCycles(plan));
    }

    @Test
    void shouldPassEmptyPlan() {
        Plan plan = new Plan("目标", List.of());
        assertDoesNotThrow(() -> validator.validateNoCycles(plan));
    }
}
