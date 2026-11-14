package com.example.jarvis.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest {

    @Test
    void shouldBuildPlanWithDependencySteps() {
        PlanStep step1 = new PlanStep("step_1", "计算 128 × 256", List.of());
        PlanStep step2 = new PlanStep("step_2", "将第一步的结果乘以 2", List.of("step_1"));
        Plan plan = new Plan("计算最终结果", List.of(step1, step2));

        assertEquals("计算最终结果", plan.goal());
        assertEquals(2, plan.steps().size());
        assertTrue(step1.dependsOn().isEmpty());
        assertEquals(List.of("step_1"), step2.dependsOn());
    }

    @Test
    void shouldNormalizeNullStepsAndDependsOn() {
        Plan plan = new Plan("目标", null);
        assertTrue(plan.steps().isEmpty());

        PlanStep step = new PlanStep("step_1", "步骤", null);
        assertTrue(step.dependsOn().isEmpty());
    }

    @Test
    void shouldRejectBlankGoal() {
        assertThrows(IllegalArgumentException.class, () -> new Plan("  ", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new Plan(null, List.of()));
    }

    @Test
    void shouldRejectBlankStepIdOrDescription() {
        assertThrows(IllegalArgumentException.class, () -> new PlanStep("", "步骤", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlanStep("step_1", null, List.of()));
    }
}
