package com.example.jarvis.execution;

import com.example.jarvis.plan.Plan;
import com.example.jarvis.plan.PlanResult;
import com.example.jarvis.plan.PlanScheduler;
import com.example.jarvis.plan.PlanStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanExecutionReportBuilder 单元测试（8.6.3.1）。
 *
 * <p>验证报告的数据来源约束：描述来自 PlanStep.description()，
 * 结果/原因来自 ExecutionContext，格式与你的设计示例逐行对齐。</p>
 */
class PlanExecutionReportBuilderTest {

    private static final PlanExecutionReportBuilder BUILDER = new PlanExecutionReportBuilder();

    private PlanResult linearPlan;   // step_1 → step_2 → step_3 线性链

    @BeforeEach
    void setUp() {
        Plan linear = new Plan("线性链", List.of(
                new PlanStep("step_1", "计算 128 × 256", List.of()),
                new PlanStep("step_2", "将上一步结果乘以 2", List.of("step_1")),
                new PlanStep("step_3", "查询相关数据", List.of("step_2"))
        ));
        linearPlan = new PlanResult(linear, new PlanScheduler().topologicalSort(linear));
    }

    @Test
    void shouldRejectNullContext() {
        assertThrows(IllegalArgumentException.class, () -> BUILDER.build(null));
    }

    @Test
    void shouldRenderMixedResultReport() {
        // 你设计文档的示例场景：step_1 SUCCESS、step_2 FAILED、step_3 SKIPPED
        ExecutionContext context = new ExecutionContext(linearPlan);
        context.markRunning("step_1");
        context.markSuccess("step_1", "32768");
        context.markRunning("step_2");
        context.markFailed("step_2", "工具执行失败");
        context.markSkipped("step_3", "前置依赖步骤未成功");

        String report = BUILDER.build(context);

        // 整体汇总段
        assertTrue(report.contains("任务执行报告"));
        assertTrue(report.contains("整体状态：部分成功"));
        assertTrue(report.contains("总步骤：3"));
        assertTrue(report.contains("成功：1"));
        assertTrue(report.contains("失败：1"));
        assertTrue(report.contains("跳过：1"));

        // 步骤详情段（拓扑顺序：step_1 → step_2 → step_3）
        int p1 = report.indexOf("[step_1] SUCCESS");
        int p2 = report.indexOf("[step_2] FAILED");
        int p3 = report.indexOf("[step_3] SKIPPED");
        assertTrue(p1 >= 0 && p2 > p1 && p3 > p2, "步骤按拓扑顺序出现");

        assertTrue(report.contains("任务：计算 128 × 256"));
        assertTrue(report.contains("结果：32768"));
        assertTrue(report.contains("任务：将上一步结果乘以 2"));
        assertTrue(report.contains("失败原因：工具执行失败"));
        assertTrue(report.contains("任务：查询相关数据"));
        assertTrue(report.contains("跳过原因：前置依赖步骤未成功"));
    }

    @Test
    void shouldRenderAllSuccessReport() {
        ExecutionContext context = new ExecutionContext(linearPlan);
        for (String stepId : List.of("step_1", "step_2", "step_3")) {
            context.markRunning(stepId);
            context.markSuccess(stepId, stepId + "结果");
        }

        String report = BUILDER.build(context);

        assertTrue(report.contains("整体状态：全部成功"));
        assertTrue(report.contains("成功：3"));
        assertTrue(report.contains("失败：0"));
        assertTrue(report.contains("跳过：0"));
        assertTrue(report.contains("结果：step_1结果"));
        // 全成功报告不含失败/跳过原因行
        assertEquals(false, report.contains("失败原因："));
        assertEquals(false, report.contains("跳过原因："));
    }

    @Test
    void shouldRenderAllFailedReport() {
        // 零成功：step_1 失败 → 其余级联跳过（与 execute 真实行为一致的终态形态）
        ExecutionContext context = new ExecutionContext(linearPlan);
        context.markRunning("step_1");
        context.markFailed("step_1", "模型调用失败");
        context.markSkipped("step_2", "前置依赖步骤未成功: step_1");
        context.markSkipped("step_3", "前置依赖步骤未成功: step_2");

        String report = BUILDER.build(context);

        assertTrue(report.contains("整体状态：整体失败"));
        assertTrue(report.contains("成功：0"));
        assertTrue(report.contains("失败：1"));
        assertTrue(report.contains("跳过：2"));
        assertTrue(report.contains("失败原因：模型调用失败"));
        assertTrue(report.contains("跳过原因：前置依赖步骤未成功: step_1"));
    }

    @Test
    void successStepWithoutResultShouldRenderPlaceholder() {
        // 无产出步骤：SUCCESS 但 result=null → "结果：（无产出）"
        ExecutionContext context = new ExecutionContext(linearPlan);
        context.markRunning("step_1");
        context.markSuccess("step_1", null);

        String report = BUILDER.build(context);

        assertTrue(report.contains("[step_1] SUCCESS"));
        assertTrue(report.contains("结果：（无产出）"));
    }

    @Test
    void missingErrorFieldShouldRenderPlaceholder() {
        // 防御：FAILED/SKIPPED 但 error 为空 → "（未记录原因）"，不输出 null 字样
        ExecutionContext context = new ExecutionContext(linearPlan);
        context.markRunning("step_1");
        context.markFailed("step_1", null);

        String report = BUILDER.build(context);

        assertTrue(report.contains("失败原因：（未记录原因）"));
        assertEquals(false, report.contains("失败原因：null"));
    }

    @Test
    void midExecutionShouldRenderPendingAndRunningSteps() {
        // 执行中途调用（防御场景）：PENDING/RUNNING 原样展示状态行
        ExecutionContext context = new ExecutionContext(linearPlan);
        context.markRunning("step_1");

        String report = BUILDER.build(context);

        assertTrue(report.contains("[step_1] RUNNING"));
        assertTrue(report.contains("[step_2] PENDING"));
        assertTrue(report.contains("[step_3] PENDING"));
        // 非终态步骤无结果/原因行
        assertTrue(report.contains("任务：计算 128 × 256"));
    }
}
