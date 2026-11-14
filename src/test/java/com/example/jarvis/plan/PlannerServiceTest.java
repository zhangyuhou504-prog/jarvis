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
                new ClassPathResource("prompts/planner-prompt.md"));
        plannerService.init();
    }

    /**
     * 构造一个"模型返回指定 content"的响应。
     */
    private void mockModelContent(String content) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.put("content", content);
        ObjectNode choice = JSON.createObjectNode();
        choice.set("message", message);
        choice.put("finish_reason", "stop");
        ObjectNode response = JSON.createObjectNode();
        response.putArray("choices").add(choice);
        when(llmClientService.chat(any(), any())).thenReturn(Mono.just(response));
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
}
