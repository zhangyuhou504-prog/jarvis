package com.example.jarvis.execution;

import com.example.jarvis.plan.Plan;
import com.example.jarvis.plan.PlanResult;
import com.example.jarvis.plan.PlanStep;
import com.example.jarvis.plan.PlanScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExecutionContext 单元测试（8.3.2 + 状态更新方法）。
 *
 * <p>覆盖：构造初始化（全 PENDING）、三个 mark 方法的状态机规则
 * （合法转换、非法转换拦截、stepId 校验）、依赖满足判断。</p>
 */
class ExecutionContextTest {

    private PlanResult planResult;

    @BeforeEach
    void setUp() {
        // 菱形：step_1、step_2 无依赖；step_3 依赖 step_1；step_4 依赖 step_2、step_3
        Plan plan = new Plan("测试计划", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of()),
                new PlanStep("step_3", "步骤三", List.of("step_1")),
                new PlanStep("step_4", "步骤四", List.of("step_2", "step_3"))
        ));
        planResult = new PlanResult(plan, new PlanScheduler().topologicalSort(plan));
    }

    // ---- 构造初始化 ----

    @Test
    void shouldInitializeAllStepsPending() {
        ExecutionContext context = new ExecutionContext(planResult);

        assertEquals(4, context.getStepExecutions().size());
        for (StepExecution exec : context.getStepExecutions().values()) {
            assertEquals(StepExecutionStatus.PENDING, exec.status());
            assertNull(exec.result());
        }
    }

    @Test
    void shouldKeepExecutionOrderInMap() {
        // LinkedHashMap 保持拓扑顺序：step_1、step_2 无依赖在前，step_4 最后
        ExecutionContext context = new ExecutionContext(planResult);
        assertEquals(List.of("step_1", "step_2", "step_3", "step_4"),
                context.getStepExecutions().keySet().stream().toList());
    }

    @Test
    void shouldRejectNullPlanResult() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionContext(null));
    }

    // ---- 合法状态转换 ----

    @Test
    void shouldTransitionPendingRunningSuccess() {
        ExecutionContext context = new ExecutionContext(planResult);

        context.markRunning("step_1");
        assertEquals(StepExecutionStatus.RUNNING, context.getStepExecution("step_1").status());

        context.markSuccess("step_1", 32768);
        StepExecution done = context.getStepExecution("step_1");
        assertEquals(StepExecutionStatus.SUCCESS, done.status());
        assertEquals(32768, done.result());
    }

    @Test
    void shouldTransitionRunningFailed() {
        ExecutionContext context = new ExecutionContext(planResult);

        context.markRunning("step_2");
        context.markFailed("step_2");

        StepExecution failed = context.getStepExecution("step_2");
        assertEquals(StepExecutionStatus.FAILED, failed.status());
        assertNull(failed.result());
    }

    @Test
    void shouldAllowNullResultOnSuccess() {
        // 无产出步骤（如"澄清需求"）：SUCCESS 但 result=null
        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        context.markSuccess("step_1", null);
        assertEquals(StepExecutionStatus.SUCCESS, context.getStepExecution("step_1").status());
        assertNull(context.getStepExecution("step_1").result());
    }

    // ---- 非法状态转换拦截 ----

    @Test
    void shouldRejectPendingToSuccess() {
        ExecutionContext context = new ExecutionContext(planResult);
        // PENDING 直接 SUCCESS：跳过了执行过程，非法
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> context.markSuccess("step_1", 123));
        assertTrue(ex.getMessage().contains("PENDING"));
    }

    @Test
    void shouldRejectPendingToFailed() {
        ExecutionContext context = new ExecutionContext(planResult);
        assertThrows(IllegalStateException.class, () -> context.markFailed("step_1"));
    }

    @Test
    void shouldRejectDoubleStart() {
        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        // RUNNING 再 markRunning：重复启动
        assertThrows(IllegalStateException.class, () -> context.markRunning("step_1"));
    }

    @Test
    void shouldRejectUpdateAfterSuccess() {
        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        context.markSuccess("step_1", 1);
        // SUCCESS 是终态：不能再 RUNNING / FAILED
        assertThrows(IllegalStateException.class, () -> context.markRunning("step_1"));
        assertThrows(IllegalStateException.class, () -> context.markFailed("step_1"));
    }

    @Test
    void shouldRejectUnknownStepId() {
        ExecutionContext context = new ExecutionContext(planResult);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> context.markRunning("step_99"));
        assertTrue(ex.getMessage().contains("step_99"));
    }

    // ---- 只读视图 ----

    @Test
    void stepExecutionsMapShouldBeReadOnly() {
        ExecutionContext context = new ExecutionContext(planResult);
        assertThrows(UnsupportedOperationException.class,
                () -> context.getStepExecutions().put("step_1", StepExecution.pending()));
    }

    // ---- 依赖满足判断 ----

    @Test
    void noDependencyStepShouldAlwaysBeSatisfied() {
        ExecutionContext context = new ExecutionContext(planResult);
        assertTrue(context.areDependenciesSatisfied("step_1"));
        assertTrue(context.areDependenciesSatisfied("step_2"));
    }

    @Test
    void dependencyShouldRequireAllSuccess() {
        ExecutionContext context = new ExecutionContext(planResult);

        // step_4 依赖 step_2、step_3：全未执行 → 不满足
        assertTrue(!context.areDependenciesSatisfied("step_4"));

        // 只完成 step_2 → 仍不满足（step_3 未跑）
        context.markRunning("step_2");
        context.markSuccess("step_2", "r2");
        assertTrue(!context.areDependenciesSatisfied("step_4"));

        // step_3 也完成 → 满足
        context.markRunning("step_3");
        context.markSuccess("step_3", "r3");
        assertTrue(context.areDependenciesSatisfied("step_4"));
    }

    @Test
    void failedDependencyShouldNotBeSatisfied() {
        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        context.markFailed("step_1");
        // 依赖失败 ≠ 满足
        assertTrue(!context.areDependenciesSatisfied("step_3"));
    }

    // ---- SKIPPED（8.4.2：依赖失败被跳过）----

    @Test
    void shouldSkipPendingStep() {
        // PENDING → SKIPPED：合法（外部原因跳过，从未启动）
        ExecutionContext context = new ExecutionContext(planResult);
        context.markSkipped("step_1");
        StepExecution skipped = context.getStepExecution("step_1");
        assertEquals(StepExecutionStatus.SKIPPED, skipped.status());
        assertNull(skipped.result());
    }

    @Test
    void shouldRejectSkipAfterStart() {
        // RUNNING / 终态不能再 SKIPPED（已启动的步骤不存在"跳过"语义）
        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        assertThrows(IllegalStateException.class, () -> context.markSkipped("step_1"));

        ExecutionContext context2 = new ExecutionContext(planResult);
        context2.markRunning("step_1");
        context2.markSuccess("step_1", 1);
        assertThrows(IllegalStateException.class, () -> context2.markSkipped("step_1"));
    }

    @Test
    void skippedDependencyShouldNotBeSatisfied() {
        // 级联核心：SKIPPED ≠ SUCCESS——依赖链下游同样不满足
        ExecutionContext context = new ExecutionContext(planResult);
        context.markSkipped("step_1");
        assertTrue(!context.areDependenciesSatisfied("step_3"));
    }

    @Test
    void shouldRejectUnknownStepIdOnSkip() {
        ExecutionContext context = new ExecutionContext(planResult);
        assertThrows(IllegalArgumentException.class, () -> context.markSkipped("step_99"));
    }
}
