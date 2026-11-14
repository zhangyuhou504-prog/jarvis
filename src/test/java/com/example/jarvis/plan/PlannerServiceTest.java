package com.example.jarvis.plan;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlannerService 单元测试：mock 模型响应，验证"content → Plan"解析链路
 * （不依赖真实模型，真实拆解质量由联调验证）。
 */
class PlannerServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmClientService llmClientService;
    private PlannerService plannerService;

    @BeforeEach
    void setUp() throws Exception {
        llmClientService = mock(LlmClientService.class);
        LlmClientFactory factory = mock(LlmClientFactory.class);
        LlmClientFactory.ResolvedLlmClient client = new LlmClientFactory.ResolvedLlmClient(
                "test-model", "test-model-name", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        when(factory.getClient(null)).thenReturn(client);

        plannerService = new PlannerService(llmClientService, factory, JSON,
                new PlanValidator(),
                new ClassPathResource("prompts/planner-prompt.md"));
        org.springframework.test.util.ReflectionTestUtils.setField(plannerService, "maxAttempts", 2);
        plannerService.init();
    }

    /**
     * 构造一个"模型返回指定 content"的响应（不 stub，供连续 thenReturn 使用）。
     */
    private ObjectNode responseOf(String content) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        ObjectNode choice = JSON.createObjectNode();
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        ObjectNode response = JSON.createObjectNode();
        response.putArray("choices").add(choice);
        return response;
    }

    /**
     * stub 模型始终返回指定 content（单次调用场景）。
     */
    private void mockModelContent(String content) {
        when(llmClientService.chat(any(), any())).thenReturn(Mono.just(responseOf(content)));
    }

    @Test
    void shouldParsePlanWithDependencies() {
        mockModelContent("""
                {
                  "goal": "计算最终结果",
                  "steps": [
                    {"stepId": "step_1", "description": "计算 128 × 256", "dependsOn": []},
                    {"stepId": "step_2", "description": "将第一步的结果乘以 2", "dependsOn": ["step_1"]}
                  ]
                }
                """);

        Plan plan = plannerService.createPlan("帮我计算 128 × 256，然后把结果乘以 2");

        assertEquals("计算最终结果", plan.goal());
        assertEquals(2, plan.steps().size());
        assertEquals("step_1", plan.steps().get(0).stepId());
        assertTrue(plan.steps().get(0).dependsOn().isEmpty());
        assertEquals(List.of("step_1"), plan.steps().get(1).dependsOn());
    }

    @Test
    void shouldStripCodeFenceBeforeParsing() {
        // 模型违规输出 markdown 代码块时仍能解析
        mockModelContent("""
                ```json
                {"goal": "目标", "steps": [{"stepId": "step_1", "description": "步骤", "dependsOn": []}]}
                ```
                """);

        Plan plan = plannerService.createPlan("目标");
        assertEquals(1, plan.steps().size());
    }

    @Test
    void shouldThrowOnInvalidJson() {
        mockModelContent("这不是 JSON，模型跑偏了");
        assertThrows(IllegalStateException.class, () -> plannerService.createPlan("目标"));
    }

    @Test
    void shouldThrowOnBlankContent() {
        mockModelContent("   ");
        assertThrows(IllegalStateException.class, () -> plannerService.createPlan("目标"));
    }

    @Test
    void shouldRejectPlanWithDuplicateStepIds() {
        // 模型返回重复 stepId 的计划 → PlanValidator 拦截
        mockModelContent("""
                {
                  "goal": "目标",
                  "steps": [
                    {"stepId": "step_1", "description": "步骤一", "dependsOn": []},
                    {"stepId": "step_1", "description": "步骤二", "dependsOn": []}
                  ]
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> plannerService.createPlan("目标"));
    }

    @Test
    void shouldRejectPlanWithMissingDependency() {
        // 模型返回依赖不存在步骤的计划 → PlanValidator 拦截
        mockModelContent("""
                {
                  "goal": "目标",
                  "steps": [
                    {"stepId": "step_1", "description": "步骤一", "dependsOn": ["step_99"]}
                  ]
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> plannerService.createPlan("目标"));
    }

    @Test
    void shouldRejectPlanWithCycle() {
        // 模型返回循环依赖的计划 → PlanValidator 拦截
        mockModelContent("""
                {
                  "goal": "目标",
                  "steps": [
                    {"stepId": "step_1", "description": "步骤一", "dependsOn": ["step_2"]},
                    {"stepId": "step_2", "description": "步骤二", "dependsOn": ["step_1"]}
                  ]
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> plannerService.createPlan("目标"));
    }

    // ---- 重试机制 ----

    @Test
    void shouldRetryAndSucceedWhenFirstAttemptFails() {
        // 首次返回非法 JSON，重试后返回合法计划 → 成功，模型被调用 2 次
        when(llmClientService.chat(any(), any()))
                .thenReturn(Mono.just(responseOf("这不是 JSON")))
                .thenReturn(Mono.just(responseOf("""
                        {"goal": "目标", "steps": [{"stepId": "step_1", "description": "步骤", "dependsOn": []}]}
                        """)));

        Plan plan = plannerService.createPlan("目标");
        assertEquals(1, plan.steps().size());

        org.mockito.ArgumentCaptor<ObjectNode> captor = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        org.mockito.Mockito.verify(llmClientService, org.mockito.Mockito.times(2)).chat(any(), captor.capture());
        // 第二次请求的 user 消息应附带修正指令与上次错误原因
        String secondUserMsg = captor.getAllValues().get(1)
                .path("messages").get(1).path("content").asText();
        assertTrue(secondUserMsg.contains("修正指令"));
        assertTrue(secondUserMsg.contains("格式不正确"));
    }

    @Test
    void shouldThrowLastErrorWhenAllAttemptsFail() {
        // 两次都返回循环依赖的计划 → 重试耗尽，抛出最后一次 IllegalArgumentException
        mockModelContent("""
                {
                  "goal": "目标",
                  "steps": [
                    {"stepId": "step_1", "description": "步骤一", "dependsOn": ["step_2"]},
                    {"stepId": "step_2", "description": "步骤二", "dependsOn": ["step_1"]}
                  ]
                }
                """);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> plannerService.createPlan("目标"));
        assertTrue(ex.getMessage().contains("循环依赖"));
        org.mockito.Mockito.verify(llmClientService, org.mockito.Mockito.times(2)).chat(any(), any());
    }

    @Test
    void shouldNotRetryWhenFirstAttemptSucceeds() {
        mockModelContent("""
                {"goal": "目标", "steps": [{"stepId": "step_1", "description": "步骤", "dependsOn": []}]}
                """);

        plannerService.createPlan("目标");
        // 一次成功不应触发重试
        org.mockito.Mockito.verify(llmClientService, org.mockito.Mockito.times(1)).chat(any(), any());
    }
}
