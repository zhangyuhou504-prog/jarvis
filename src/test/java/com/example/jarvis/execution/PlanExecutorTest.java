package com.example.jarvis.execution;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.plan.Plan;
import com.example.jarvis.plan.PlanResult;
import com.example.jarvis.plan.PlanScheduler;
import com.example.jarvis.plan.PlanStep;
import com.example.jarvis.service.ToolLoopResult;
import com.example.jarvis.service.ToolLoopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanExecutor 单元测试（8.4.2 依赖跳过 + 8.4.3 步骤执行 + 8.4.4 状态更新）。
 *
 * <p>ToolLoopService 用 mock：每个 SUCCESS 步骤对应一次 run() 调用，
 * 返回预设文本作为"模型最终回答"（决策点 A：result 存最终文本回答）。</p>
 */
class PlanExecutorTest {

    private ToolLoopService toolLoopService;
    private PlanExecutor executor;
    private LlmClientFactory.ResolvedLlmClient client;
    private PlanResult linearPlan;   // step_1 → step_2 → step_3 线性链
    private PlanResult diamondPlan;  // 菱形：step_3 依赖 step_1；step_4 依赖 step_2、step_3

    @BeforeEach
    void setUp() {
        toolLoopService = mock(ToolLoopService.class);
        LlmClientFactory factory = mock(LlmClientFactory.class);
        client = new LlmClientFactory.ResolvedLlmClient(
                "test-model", "test-model-name", null, null, null,
                "http://localhost", WebClient.create());
        when(factory.getClient(null)).thenReturn(client);

        executor = new PlanExecutor(
                new tools.jackson.databind.ObjectMapper(),
                toolLoopService,
                factory,
                new ClassPathResource("prompts/step-execution-prompt.md"));

        Plan linear = new Plan("线性链", List.of(
                new PlanStep("step_1", "步骤一", List.of()),
                new PlanStep("step_2", "步骤二", List.of("step_1")),
                new PlanStep("step_3", "步骤三", List.of("step_2"))
        ));
        linearPlan = new PlanResult(linear, new PlanScheduler().topologicalSort(linear));

        Plan diamond = new Plan("菱形", List.of(
                new PlanStep("step_1", "甲", List.of()),
                new PlanStep("step_2", "乙", List.of()),
                new PlanStep("step_3", "丙", List.of("step_1")),
                new PlanStep("step_4", "丁", List.of("step_2", "step_3"))
        ));
        diamondPlan = new PlanResult(diamond, new PlanScheduler().topologicalSort(diamond));
    }

    // ---- 8.4.2 依赖检查与跳过（失败级联） ----

    @Test
    void shouldCascadeSkipWhenDependencyFails() {
        // step_1 失败 → step_2、step_3 级联 SKIPPED（决策点 B：markFailed 后 continue）
        when(toolLoopService.run(eq(client), any()))
                .thenThrow(new RuntimeException("模型调用失败"));

        ExecutionContext context = executor.execute(linearPlan);

        assertEquals(StepExecutionStatus.FAILED, context.getStepExecution("step_1").status());
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_2").status());
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_3").status());
        // 失败步骤只调用一次（没有重试，重试策略在 8.6）
        verify(toolLoopService, times(1)).run(any(), any());
    }

    @Test
    void shouldContinueIndependentStepsAfterFailure() {
        // 决策点 B 的核心：step_1 失败不波及无依赖的 step_2 —— 依赖图的价值
        when(toolLoopService.run(eq(client), any()))
                .thenThrow(new RuntimeException("模型调用失败"))       // step_1
                .thenReturn(new ToolLoopResult("乙的独立结果", true));  // step_2

        ExecutionContext context = executor.execute(diamondPlan);

        assertEquals(StepExecutionStatus.FAILED, context.getStepExecution("step_1").status());
        assertEquals(StepExecutionStatus.SUCCESS, context.getStepExecution("step_2").status());
        // step_3 依赖 step_1（FAILED）→ SKIPPED；step_4 依赖 step_3（SKIPPED）→ SKIPPED
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_3").status());
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_4").status());
        // 失败 + 成功各一次，SKIPPED 的两步不调用模型
        verify(toolLoopService, times(2)).run(any(), any());
    }

    @Test
    void shouldExecuteAllStepsSuccessfully() {
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("甲结果", true))
                .thenReturn(new ToolLoopResult("乙结果", true))
                .thenReturn(new ToolLoopResult("丙结果", true))
                .thenReturn(new ToolLoopResult("丁结果", true));

        ExecutionContext context = executor.execute(diamondPlan);

        for (String stepId : List.of("step_1", "step_2", "step_3", "step_4")) {
            assertEquals(StepExecutionStatus.SUCCESS, context.getStepExecution(stepId).status());
        }
        // 决策点 A：result 存 ToolLoopService 最终文本回答
        assertEquals("甲结果", context.getStepExecution("step_1").result());
        assertEquals("丁结果", context.getStepExecution("step_4").result());
        verify(toolLoopService, times(4)).run(any(), any());
    }

    // ---- 8.4.3.2 步骤消息组装（捕获发给 ToolLoopService 的 messages 验证） ----

    @Test
    void userMessageShouldContainTaskAndDirectDependencyResultsOnly() throws Exception {
        // 方案 B：step_4 只拿到直接依赖 step_2、step_3 的结果，不含 step_1
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("甲结果", true))
                .thenReturn(new ToolLoopResult("乙结果", true))
                .thenReturn(new ToolLoopResult("丙结果", true))
                .thenReturn(new ToolLoopResult("丁结果", true));

        executor.execute(diamondPlan);

        // 第 4 次调用（step_4）的 user 消息
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(toolLoopService, times(4)).run(eq(client), captor.capture());
        String userContent = captor.getAllValues().get(3).get(1).path("content").asText();

        assertTrue(userContent.contains("当前步骤任务：丁"), "应包含当前步骤任务");
        assertTrue(userContent.contains("前置依赖步骤的结果"), "应包含依赖结果段");
        assertTrue(userContent.contains("- step_2 = 乙结果"), "应包含 step_2 结果");
        assertTrue(userContent.contains("- step_3 = 丙结果"), "应包含 step_3 结果");
        assertEquals(false, userContent.contains("step_1"), "不应包含非直接依赖 step_1");
    }

    @Test
    void messagesShouldFollowSystemThenUserStructure() {
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("结果", true));

        executor.execute(linearPlan);

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(toolLoopService, times(3)).run(eq(client), captor.capture());
        ArrayNode messages = captor.getAllValues().get(0);

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).path("role").asText());
        assertEquals("user", messages.get(1).path("role").asText());
        // system 是步骤执行 Prompt
        assertTrue(messages.get(0).path("content").asText().contains("步骤执行器"));
    }

    @Test
    void noDependencyStepShouldHaveNoDependencySection() {
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("甲结果", true));

        executor.execute(linearPlan);

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(toolLoopService, times(3)).run(eq(client), captor.capture());
        String userContent = captor.getAllValues().get(0).get(1).path("content").asText();

        assertTrue(userContent.contains("当前步骤任务：步骤一"));
        assertEquals(false, userContent.contains("前置依赖步骤的结果"), "无依赖步骤不应有依赖结果段");
    }

    @Test
    void nullDependencyResultShouldRenderAsNoOutput() {
        // 依赖 SUCCESS 但产出 null → 渲染为"（无产出）"，不出现 "null" 字样误导模型
        // （走 buildStepMessagesForTest 直接验证渲染，execute 路径中 ToolLoopService
        //  永不返回 null——asText("") 兜底，null result 只出现在无产出步骤）
        ExecutionContext context = new ExecutionContext(linearPlan);
        success(context, "step_1", null);

        PlanStep step2 = new PlanStep("step_2", "步骤二", List.of("step_1"));
        Map<String, Object> depResults = executor.getDependencyResultsForTest(step2, context);
        ArrayNode messages = executor.buildStepMessagesForTest(step2, depResults);
        String userContent = messages.get(1).path("content").asText();

        assertTrue(userContent.contains("- step_1 = （无产出）"));
        assertEquals(false, userContent.contains("= null"));
    }

    // ---- 8.4.3.3 闭环成败接线（ToolLoopResult.success）----

    @Test
    void loopFailureShouldMarkStepFailedAndNotStoreAnswer() {
        // 闭环未成功完成（success=false）→ 步骤 FAILED，answer 不作为产出存入 result
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("工具执行失败: 参数非法", false));

        ExecutionContext context = executor.execute(linearPlan);

        assertEquals(StepExecutionStatus.FAILED, context.getStepExecution("step_1").status());
        assertNull(context.getStepExecution("step_1").result());
        // 下游级联跳过
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_2").status());
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_3").status());
    }

    @Test
    void loopFailureShouldNotBlockIndependentSteps() {
        // success=false 与抛异常同等对待：失败步骤的兄弟步骤照常执行（决策点 B）
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("甲的失败说明", false))
                .thenReturn(new ToolLoopResult("乙的独立结果", true));

        ExecutionContext context = executor.execute(diamondPlan);

        assertEquals(StepExecutionStatus.FAILED, context.getStepExecution("step_1").status());
        assertEquals(StepExecutionStatus.SUCCESS, context.getStepExecution("step_2").status());
        assertEquals("乙的独立结果", context.getStepExecution("step_2").result());
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_3").status());
        assertEquals(StepExecutionStatus.SKIPPED, context.getStepExecution("step_4").status());
    }

    // ---- 8.4.3.1 依赖结果提取（getDependencyResults）----

    @Test
    void shouldExtractOnlyDirectDependencyResults() {
        // 方案 B：只取直接依赖。step_4 依赖 step_2、step_3 → 只返回这两个的结果，
        // 不含 step_1（step_3 的依赖，与 step_4 无直接关系）
        ExecutionContext context = new ExecutionContext(diamondPlan);
        success(context, "step_1", "甲结果");
        success(context, "step_2", "乙结果");
        success(context, "step_3", "丙结果");

        PlanStep step4 = new PlanStep("step_4", "丁", List.of("step_2", "step_3"));
        Map<String, Object> results = executor.getDependencyResultsForTest(step4, context);

        assertEquals(2, results.size());
        assertEquals("乙结果", results.get("step_2"));
        assertEquals("丙结果", results.get("step_3"));
        // 保持 dependsOn 声明顺序
        assertEquals(List.of("step_2", "step_3"), results.keySet().stream().toList());
    }

    @Test
    void shouldReturnEmptyMapForNoDependencyStep() {
        // dependsOn 为空 → 空 Map（不是 null），可直接用于组装上下文
        ExecutionContext context = new ExecutionContext(diamondPlan);
        PlanStep step1 = new PlanStep("step_1", "甲", List.of());

        Map<String, Object> results = executor.getDependencyResultsForTest(step1, context);

        assertEquals(0, results.size());
    }

    @Test
    void shouldIncludeNullResultOfSuccessfulDependency() {
        // 依赖 SUCCESS 但产出为 null（无产出步骤）：应包含 key、值为 null——
        // 与"依赖不存在"区分开
        ExecutionContext context = new ExecutionContext(diamondPlan);
        success(context, "step_1", null);

        PlanStep step3 = new PlanStep("step_3", "丙", List.of("step_1"));
        Map<String, Object> results = executor.getDependencyResultsForTest(step3, context);

        assertEquals(1, results.size());
        assertNull(results.get("step_1"));
    }

    @Test
    void shouldThrowWhenDependencyNotSuccessful() {
        // 防御性兜底：依赖 FAILED 时取数应明确报错（正常路径有守门不会走到这）
        ExecutionContext context = new ExecutionContext(diamondPlan);
        context.markRunning("step_1");
        context.markFailed("step_1");

        PlanStep step3 = new PlanStep("step_3", "丙", List.of("step_1"));

        RuntimeException ex = assertThrows(IllegalStateException.class,
                () -> executor.getDependencyResultsForTest(step3, context));
        assertTrue(ex.getMessage().contains("step_1"));
    }

    // ---- 通用 ----

    @Test
    void shouldRejectNullPlanResult() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(null));
    }

    @Test
    void returnedContextShouldCoverAllSteps() {
        when(toolLoopService.run(eq(client), any()))
                .thenReturn(new ToolLoopResult("结果", true));

        // 返回的 ExecutionContext 覆盖计划全部步骤（key 集合 = 步骤集合，拓扑序）
        ExecutionContext context = executor.execute(diamondPlan);

        assertEquals(4, context.getStepExecutions().size());
        assertEquals(List.of("step_1", "step_2", "step_3", "step_4"),
                context.getStepExecutions().keySet().stream().toList());
    }

    /**
     * 便捷：把某步骤标记为 SUCCESS（带产出）。
     */
    private void success(ExecutionContext context, String stepId, Object result) {
        context.markRunning(stepId);
        context.markSuccess(stepId, result);
    }
}
