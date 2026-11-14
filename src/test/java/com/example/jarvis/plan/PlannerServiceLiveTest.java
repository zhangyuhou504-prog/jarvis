package com.example.jarvis.plan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PlannerService 真实模型联调：验证模型能否真正生成带依赖关系的计划。
 *
 * <p>注意：本测试真实调用模型网关（非 mock），用于人工验收拆解质量，
 * 输出完整计划到测试日志。模型输出有随机性，断言只校验结构下限。</p>
 */
@SpringBootTest
class PlannerServiceLiveTest {

    @Autowired
    private PlannerService plannerService;

    @Test
    void shouldGeneratePlanWithDependencies() {
        Plan plan = plannerService.createPlan("帮我计算 128 × 256，然后把结果乘以 2");

        System.out.println("=== 生成的计划 ===");
        System.out.println("goal: " + plan.goal());
        plan.steps().forEach(s ->
                System.out.println("  " + s.stepId() + " | " + s.description() + " | dependsOn=" + s.dependsOn()));

        assertFalse(plan.steps().isEmpty(), "计划不应为空");
    }

    @Test
    void shouldGenerateSingleStepForSimpleGoal() {
        Plan plan = plannerService.createPlan("现在几点了？");

        System.out.println("=== 简单目标的计划 ===");
        System.out.println("goal: " + plan.goal());
        plan.steps().forEach(s ->
                System.out.println("  " + s.stepId() + " | " + s.description() + " | dependsOn=" + s.dependsOn()));

        assertFalse(plan.steps().isEmpty(), "计划不应为空");
    }
}
