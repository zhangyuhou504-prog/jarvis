package com.example.jarvis.service;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import com.example.jarvis.tool.CurrentTimeTool;
import com.example.jarvis.tool.MultiplyTool;
import com.example.jarvis.tool.ToolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolLoopService 单元测试：用 mock 的 LlmClientService 模拟"先返回 tool_calls、
 * 再返回最终答案"的两轮响应，验证闭环逻辑（不依赖真实模型）。
 */
class ToolLoopServiceTest {

    private static final JsonMapper JSON = new JsonMapper();

    private LlmClientService llmClientService;
    private ToolLoopService toolLoopService;
    private LlmClientFactory.ResolvedLlmClient client;

    @BeforeEach
    void setUp() {
        llmClientService = mock(LlmClientService.class);
        CurrentTimeTool currentTimeTool = new CurrentTimeTool();
        ReflectionTestUtils.setField(currentTimeTool, "defaultTimezone", "Asia/Shanghai");
        ToolExecutor toolExecutor = new ToolExecutor(new MultiplyTool(), currentTimeTool, new JsonMapper());
        toolLoopService = new ToolLoopService(llmClientService, toolExecutor);
        ReflectionTestUtils.setField(toolLoopService, "maxRounds", 5);
        ReflectionTestUtils.setField(toolLoopService, "callTimeoutSeconds", 30L);

        client = new LlmClientFactory.ResolvedLlmClient(
                "test-model", "test-model-name", null, null, null,
                "http://localhost", WebClient.create());
    }

    private ArrayNode initialMessages() {
        ArrayNode messages = JSON.createArrayNode();
        ObjectNode user = JSON.createObjectNode();
        user.put("role", "user");
        user.put("content", "帮我计算 128 × 256");
        messages.add(user);
        return messages;
    }

    private JsonNode toolCallsResponse() throws Exception {
        // 模拟模型第一轮：请求调用 multiply
        String json = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_001",
                        "type": "function",
                        "function": {
                          "name": "multiply",
                          "arguments": "{\\"a\\": 128, \\"b\\": 256}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }]
                }
                """;
        return JSON.readTree(json);
    }

    private JsonNode finalAnswerResponse() throws Exception {
        // 模拟模型第二轮：给出最终答案
        String json = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "128 × 256 = 32768"
                    },
                    "finish_reason": "stop"
                  }]
                }
                """;
        return JSON.readTree(json);
    }

    @Test
    void shouldExecuteToolAndReturnFinalAnswer() throws Exception {
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(toolCallsResponse()))
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));

        String answer = toolLoopService.run(client, initialMessages());

        assertEquals("128 × 256 = 32768", answer);
        verify(llmClientService, times(2)).chat(any(), any());

        // 验证第二轮请求的 messages 里正确回传了 tool 结果
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(llmClientService, times(2)).chat(any(), captor.capture());
        ObjectNode secondRequest = captor.getAllValues().get(1);
        JsonNode messages = secondRequest.get("messages");
        assertTrue(messages.isArray());
        // 第 2 条应为 assistant(tool_calls)，第 3 条应为 role=tool 且内容为 32768
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertTrue(messages.get(1).path("tool_calls").isArray());
        assertEquals("tool", messages.get(2).path("role").asText());
        assertEquals("call_001", messages.get(2).path("tool_call_id").asText());
        assertEquals("32768", messages.get(2).path("content").asText());
        // 请求体应带 tools 定义
        assertTrue(secondRequest.path("tools").isArray());
        assertEquals("multiply", secondRequest.path("tools").get(0).path("function").path("name").asText());
    }

    @Test
    void shouldReturnDirectlyWhenNoToolCalls() throws Exception {
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));

        String answer = toolLoopService.run(client, initialMessages());

        assertEquals("128 × 256 = 32768", answer);
        verify(llmClientService, times(1)).chat(any(), any());
    }

    @Test
    void shouldStopWhenMaxRoundsExceeded() throws Exception {
        // 模型每轮都请求工具，达到上限后应抛异常
        ReflectionTestUtils.setField(toolLoopService, "maxRounds", 2);
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(toolCallsResponse()));

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> toolLoopService.run(client, initialMessages()));
        assertTrue(ex.getMessage().contains("工具调用轮数达到上限"));
        verify(llmClientService, times(3)).chat(any(), any()); // 2 轮工具 + 第 3 次请求后超限
    }

    @Test
    void shouldFeedBackToolFailureInsteadOfThrowing() throws Exception {
        // 模型请求一个未知工具 → 执行器抛异常 → 失败原因回传给模型，循环继续
        String json = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_002",
                        "type": "function",
                        "function": {
                          "name": "not_exist_tool",
                          "arguments": "{}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }]
                }
                """;
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(JSON.readTree(json)))
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));

        String answer = toolLoopService.run(client, initialMessages());

        assertEquals("128 × 256 = 32768", answer);
        // 验证失败结果被回传
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(llmClientService, times(2)).chat(any(), captor.capture());
        JsonNode messages = captor.getAllValues().get(1).get("messages");
        assertEquals("tool", messages.get(2).path("role").asText());
        assertTrue(messages.get(2).path("content").asText().contains("工具执行失败"));
    }
}
