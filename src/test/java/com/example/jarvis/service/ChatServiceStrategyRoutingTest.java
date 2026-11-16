package com.example.jarvis.service;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.execution.ExecutionContext;
import com.example.jarvis.execution.PlanExecutionReportBuilder;
import com.example.jarvis.execution.PlanExecutor;
import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.model.IntentResult;
import com.example.jarvis.model.IntentType;
import com.example.jarvis.plan.Plan;
import com.example.jarvis.plan.PlanOrchestrationService;
import com.example.jarvis.plan.PlanResult;
import com.example.jarvis.plan.PlanScheduler;
import com.example.jarvis.plan.PlanStep;
import com.example.jarvis.router.ModelRouter;
import com.example.jarvis.strategy.ExecutionStrategy;
import com.example.jarvis.strategy.StrategyContext;
import com.example.jarvis.strategy.StrategyResult;
import com.example.jarvis.strategy.StrategyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ArrayNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatService 策略分流测试（8.2.7 + 8.6.3.2）。
 *
 * <p>验证 chatWithTools 编排链路：意图 → 策略判断（上下文完整传递）→
 * DIRECT 走工具闭环 / PLANNED 走规划执行链路（生成计划 → 执行 → 报告）。
 * 重点用 Mockito 验证：</p>
 * <ul>
 *   <li>conversationId 与 history 原样进入 StrategyContext（不丢失）</li>
 *   <li>PLANNED 不触发模型路由与工具闭环（走规划链路）</li>
 *   <li>PLANNED 产出执行报告作为回答，异常上抛不降级</li>
 * </ul>
 */
class ChatServiceStrategyRoutingTest {

    private ChatClient chatClient;
    private LlmClientFactory llmClientFactory;
    private ModelRouter modelRouter;
    private IntentService intentService;
    private ToolLoopService toolLoopService;
    private StrategyResolver strategyResolver;
    private PromptTemplateService promptTemplateService;
    private ConversationService conversationService;
    private PlanOrchestrationService planOrchestrationService;
    private PlanExecutor planExecutor;
    private PlanExecutionReportBuilder planExecutionReportBuilder;
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        llmClientFactory = mock(LlmClientFactory.class);
        modelRouter = mock(ModelRouter.class);
        intentService = mock(IntentService.class);
        toolLoopService = mock(ToolLoopService.class);
        strategyResolver = mock(StrategyResolver.class);
        promptTemplateService = mock(PromptTemplateService.class);
        conversationService = mock(ConversationService.class);
        planOrchestrationService = mock(PlanOrchestrationService.class);
        planExecutor = mock(PlanExecutor.class);
        planExecutionReportBuilder = mock(PlanExecutionReportBuilder.class);

        chatService = new ChatService(chatClient, llmClientFactory, modelRouter,
                intentService, toolLoopService, strategyResolver,
                promptTemplateService, conversationService,
                planOrchestrationService, planExecutor, planExecutionReportBuilder);
        ReflectionTestUtils.setField(chatService, "intentEnabled", true);
        // 历史快照上限（默认 @Value 注入在生产环境生效，单测需手动设值）
        ReflectionTestUtils.setField(chatService, "maxHistoryMessages", 20);
        when(promptTemplateService.render(anyString())).thenReturn("system-prompt");
    }

    private ChatRequest requestOf(String message, String conversationId) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        request.setConversationId(conversationId);
        return request;
    }

    // ---- 场景 1/2：策略 DIRECT → 走工具闭环 ----

    @Test
    void directStrategyShouldGoThroughToolLoop() {
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.GENERAL_QA, 0.9, "测试"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.DIRECT, "普通知识问答"));
        LlmClientFactory.ResolvedLlmClient client = new LlmClientFactory.ResolvedLlmClient(
                "mxbc-qwen", "qwen3.8-27b", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        when(llmClientFactory.getClient(anyString())).thenReturn(client);
        when(modelRouter.route(any(), any())).thenReturn("mxbc-qwen");
        when(toolLoopService.run(any(), any())).thenReturn(new ToolLoopResult("工具回答", true));

        ChatResponse response = chatService.chatWithTools(requestOf("什么是 Redis？", "conv-1"));

        assertEquals("工具回答", response.getAnswer());
        verify(toolLoopService, times(1)).run(any(), any());
        // DIRECT 不抛 PLANNED 拒绝
        verify(strategyResolver, times(1)).resolve(any());
    }

    // ---- 场景 3/4：策略 PLANNED → 规划执行链路（8.6.3.2）----

    @Test
    void plannedStrategyShouldExecutePlanAndReturnReport() {
        // PLANNED 全链路：createPlan → execute → 报告作为回答，不碰 DIRECT 的路由/工具闭环
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.DATA_QUERY, 0.9, "测试"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.PLANNED, "第二步依赖第一步的计算结果"));

        Plan plan = new Plan("计算任务", List.of(
                new PlanStep("step_1", "计算 128 × 256", List.of()),
                new PlanStep("step_2", "将上一步结果乘以 2", List.of("step_1"))
        ));
        PlanResult planResult = new PlanResult(plan, new PlanScheduler().topologicalSort(plan));
        when(planOrchestrationService.createPlan("计算 128×256 然后乘以 2"))
                .thenReturn(planResult);

        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        context.markSuccess("step_1", "32768");
        context.markRunning("step_2");
        context.markSuccess("step_2", "65536");
        when(planExecutor.execute(planResult)).thenReturn(context);
        when(planExecutionReportBuilder.build(context)).thenReturn("任务执行报告\n整体状态：全部成功");

        ChatResponse response = chatService.chatWithTools(requestOf("计算 128×256 然后乘以 2", null));

        assertEquals("任务执行报告\n整体状态：全部成功", response.getAnswer());
        // 规划链路各环节恰好一次
        verify(planOrchestrationService, times(1)).createPlan("计算 128×256 然后乘以 2");
        verify(planExecutor, times(1)).execute(planResult);
        verify(planExecutionReportBuilder, times(1)).build(context);
        // PLANNED 绝不走 DIRECT 链路（模型路由 / 工具闭环零调用）
        verify(modelRouter, never()).route(any(), any());
        verify(toolLoopService, never()).run(any(), any());
    }

    @Test
    void plannedPathShouldPropagatePlanGenerationFailure() {
        // 计划生成失败 → 异常向上抛出，绝不静默降级成 DIRECT
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.DATA_QUERY, 0.9, "测试"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.PLANNED, "多步骤依赖"));
        when(planOrchestrationService.createPlan(anyString()))
                .thenThrow(new IllegalStateException("计划生成失败：模型无返回"));

        assertThrows(IllegalStateException.class,
                () -> chatService.chatWithTools(requestOf("计算 128×256 然后乘以 2", null)));

        // 生成失败后不执行任何后续环节
        verify(planExecutor, never()).execute(any());
        verify(planExecutionReportBuilder, never()).build(any());
        verify(toolLoopService, never()).run(any(), any());
    }

    @Test
    void plannedPathShouldSaveTurnWhenConversationExists() {
        // PLANNED 与 DIRECT 同口径：conversation 存在时保存完整问答
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.DATA_QUERY, 0.9, "测试"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.PLANNED, "多步骤依赖"));

        com.example.jarvis.domain.Conversation conversation =
                new com.example.jarvis.domain.Conversation("conv-7");
        when(conversationService.getConversation("conv-7")).thenReturn(conversation);

        Plan plan = new Plan("单步", List.of(new PlanStep("step_1", "唯一步骤", List.of())));
        PlanResult planResult = new PlanResult(plan, new PlanScheduler().topologicalSort(plan));
        when(planOrchestrationService.createPlan(anyString())).thenReturn(planResult);

        ExecutionContext context = new ExecutionContext(planResult);
        context.markRunning("step_1");
        context.markSuccess("step_1", "完成");
        when(planExecutor.execute(planResult)).thenReturn(context);
        when(planExecutionReportBuilder.build(context)).thenReturn("报告");

        chatService.chatWithTools(requestOf("帮我规划并执行", "conv-7"));

        // 问答对完整写入（user = 用户消息，assistant = 报告）
        assertEquals(2, conversation.getMessages().size());
        assertEquals("帮我规划并执行", conversation.getMessages().get(0).getContent());
        assertEquals("报告", conversation.getMessages().get(1).getContent());
    }

    // ---- 场景 5：Java 规则命中 → 不调用小模型（StrategyResolver 内部已验证，
    //      这里验证编排层把 context 完整交给 resolver，由 resolver 自行决定规则/模型） ----

    @Test
    void strategyContextShouldCarryConversationIdAndHistory() {
        com.example.jarvis.domain.Conversation conversation =
                new com.example.jarvis.domain.Conversation("conv-42");
        conversation.getMessages().add(new com.example.jarvis.domain.ChatMessage("user", "上一条消息"));
        conversation.getMessages().add(new com.example.jarvis.domain.ChatMessage("assistant", "上一个回答"));
        when(conversationService.getConversation("conv-42")).thenReturn(conversation);
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.UNKNOWN, 0.0, "识别失败"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.DIRECT, "规则命中"));
        LlmClientFactory.ResolvedLlmClient client = new LlmClientFactory.ResolvedLlmClient(
                "mxbc-qwen", "qwen3.8-27b", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        when(llmClientFactory.getClient(anyString())).thenReturn(client);
        when(modelRouter.route(any(), any())).thenReturn("mxbc-qwen");
        when(toolLoopService.run(any(), any())).thenReturn(new ToolLoopResult("回答", true));

        chatService.chatWithTools(requestOf("接着说", "conv-42"));

        // 捕获传入 StrategyResolver 的 StrategyContext，验证上下文无丢失
        ArgumentCaptor<StrategyContext> captor = ArgumentCaptor.forClass(StrategyContext.class);
        verify(strategyResolver).resolve(captor.capture());
        StrategyContext ctx = captor.getValue();
        assertEquals("接着说", ctx.userMessage());
        assertEquals("conv-42", ctx.conversationId());
        assertEquals(IntentType.UNKNOWN, ctx.intentResult().intent());
        assertEquals(2, ctx.history().size());
        assertEquals("上一条消息", ctx.history().get(0).getContent());
    }

    // ---- 场景 6：conversationId 为空（单轮对话）→ history 为空但字段不缺 ----

    @Test
    void strategyContextShouldHandleNullConversation() {
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.GENERAL_QA, 0.9, "测试"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.DIRECT, "普通问答"));
        LlmClientFactory.ResolvedLlmClient client = new LlmClientFactory.ResolvedLlmClient(
                "mxbc-qwen", "qwen3.8-27b", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        when(llmClientFactory.getClient(anyString())).thenReturn(client);
        when(modelRouter.route(any(), any())).thenReturn("mxbc-qwen");
        when(toolLoopService.run(any(), any())).thenReturn(new ToolLoopResult("回答", true));

        chatService.chatWithTools(requestOf("你好", null));

        ArgumentCaptor<StrategyContext> captor = ArgumentCaptor.forClass(StrategyContext.class);
        verify(strategyResolver).resolve(captor.capture());
        StrategyContext ctx = captor.getValue();
        assertEquals("你好", ctx.userMessage());
        assertEquals(null, ctx.conversationId());
        assertEquals(List.of(), ctx.history());
    }

    // ---- 补充：模型调用异常在 DIRECT 路径的正常传播不受分流影响 ----

    @Test
    void directPathShouldStillPropagateToolLoopErrors() {
        when(intentService.recognize(anyString()))
                .thenReturn(new IntentResult(IntentType.GENERAL_QA, 0.9, "测试"));
        when(strategyResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.DIRECT, "规则命中"));
        LlmClientFactory.ResolvedLlmClient client = new LlmClientFactory.ResolvedLlmClient(
                "mxbc-qwen", "qwen3.8-27b", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        when(llmClientFactory.getClient(anyString())).thenReturn(client);
        when(modelRouter.route(any(), any())).thenReturn("mxbc-qwen");
        when(toolLoopService.run(any(), any())).thenThrow(new RuntimeException("工具闭环失败"));

        assertThrows(RuntimeException.class,
                () -> chatService.chatWithTools(requestOf("你好", null)));
    }
}
