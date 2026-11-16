package com.example.jarvis.service;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import com.example.jarvis.tool.CurrentTimeTool;
import com.example.jarvis.tool.MultiplyTool;
import com.example.jarvis.tool.ToolExecutor;
import com.example.jarvis.tool.ToolRegistry;
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
        ToolRegistry registry = new ToolRegistry(java.util.List.of(new MultiplyTool(), currentTimeTool));
        ToolExecutor toolExecutor = new ToolExecutor(registry, new JsonMapper());
        toolLoopService = new ToolLoopService(llmClientService, toolExecutor, registry);
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

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals("128 × 256 = 32768", result.answer());
        // 第一次工具调用成功 → 无未恢复失败 → success=true（规则 3 + 规则 1 未触发）
        assertEquals(true, result.success());
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

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals("128 × 256 = 32768", result.answer());
        assertEquals(true, result.success());
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

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals("128 × 256 = 32768", result.answer());
        // 失败（无重试恢复）：not_exist_tool 失败后模型直接文本作答，
        // 未再成功调用该工具 → 失败未恢复 → success=false（恢复规则 1+3）
        assertEquals(false, result.success());
        // 验证失败结果被回传
        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(llmClientService, times(2)).chat(any(), captor.capture());
        JsonNode messages = captor.getAllValues().get(1).get("messages");
        assertEquals("tool", messages.get(2).path("role").asText());
        assertTrue(messages.get(2).path("content").asText().contains("工具执行失败"));
    }

    // ---- 8.4.3.3 恢复模式：失败记录与清除（按工具名匹配） ----

    /**
     * 程序化构造"模型请求调用 multiply"的响应（arguments 为原始 JSON 字符串，
     * 非法参数会触发真实执行失败——MultiplyTool 的 BigInteger 解析抛异常）。
     */
    private JsonNode multiplyCallResponse(String callId, String argumentsJson) {
        ObjectNode function = JSON.createObjectNode();
        function.put("name", "multiply");
        function.put("arguments", argumentsJson);

        ObjectNode toolCall = JSON.createObjectNode();
        toolCall.put("id", callId);
        toolCall.put("type", "function");
        toolCall.set("function", function);

        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        message.putArray("tool_calls").add(toolCall);

        ObjectNode choice = JSON.createObjectNode();
        choice.set("message", message);
        choice.put("finish_reason", "tool_calls");

        ObjectNode response = JSON.createObjectNode();
        response.putArray("choices").add(choice);
        return response;
    }

    @Test
    void failureThenRetrySuccessShouldRecover() throws Exception {
        // 重试成功：第一次参数非法失败 → 模型修正参数重试同名工具成功
        // → 失败记录被清除 → 最终文本回答 → success=true（恢复规则 1+2+3）
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(
                        multiplyCallResponse("call_001", "{\"a\": \"not_a_number\", \"b\": 2}"))) // 失败
                .thenReturn(reactor.core.publisher.Mono.just(
                        multiplyCallResponse("call_002", "{\"a\": 128, \"b\": 256}")))            // 重试成功
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));            // 最终回答

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals(true, result.success());
        assertEquals("128 × 256 = 32768", result.answer());
        verify(llmClientService, times(3)).chat(any(), any());
    }

    @Test
    void failureThenRetryStillFailsShouldBeUnresolved() throws Exception {
        // 重试失败：两次参数都非法 → 失败记录未被清除 → 模型放弃转文本作答
        // → success=false（恢复规则 1+1+3：记录非空）
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(
                        multiplyCallResponse("call_001", "{\"a\": \"bad\", \"b\": 2}")))       // 失败
                .thenReturn(reactor.core.publisher.Mono.just(
                        multiplyCallResponse("call_002", "{\"a\": \"still_bad\", \"b\": 2}"))) // 仍失败
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));          // 放弃，文本作答

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals(false, result.success());
        // answer 仍携带模型最终回答（供诊断），但闭环整体判定为失败
        assertEquals("128 × 256 = 32768", result.answer());
        verify(llmClientService, times(3)).chat(any(), any());
    }

    // ---- 8.4.3.3 边界修正：计数抵消（每次成功最多抵一次同名失败） ----

    /**
     * 程序化构造"模型同时请求多个 multiply 调用"的响应（同一轮并发多次调用，
     * arguments 可各不相同——用于同轮多次失败场景）。
     */
    private JsonNode multiplyMultiCallResponse(String[] argumentsList) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.putNull("content");
        var toolCallsArray = message.putArray("tool_calls");
        for (int i = 0; i < argumentsList.length; i++) {
            ObjectNode function = JSON.createObjectNode();
            function.put("name", "multiply");
            function.put("arguments", argumentsList[i]);

            ObjectNode toolCall = JSON.createObjectNode();
            toolCall.put("id", "call_" + (i + 1));
            toolCall.put("type", "function");
            toolCall.set("function", function);
            toolCallsArray.add(toolCall);
        }

        ObjectNode choice = JSON.createObjectNode();
        choice.set("message", message);
        choice.put("finish_reason", "tool_calls");

        ObjectNode response = JSON.createObjectNode();
        response.putArray("choices").add(choice);
        return response;
    }

    @Test
    void twoFailuresOneSuccessShouldLeaveOneUnresolved() throws Exception {
        // 边界：同一轮 multiply 两次失败（计数=2）→ 下一轮一次成功（计数=1）
        // → 一次成功只抵一次失败 → 记录非空 → success=false
        // （Set 版本会把两次失败一起清除，误判 success=true——本测试锁定计数语义）
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(multiplyMultiCallResponse(new String[]{
                        "{\"a\": \"bad1\", \"b\": 2}",       // 失败 → 计数 1
                        "{\"a\": \"bad2\", \"b\": 2}"        // 失败 → 计数 2
                })))
                .thenReturn(reactor.core.publisher.Mono.just(
                        multiplyCallResponse("call_003", "{\"a\": 3, \"b\": 4}"))) // 成功 → 计数 1
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals(false, result.success(), "两次失败一次成功应剩 1 笔未恢复，非空 → false");
        verify(llmClientService, times(3)).chat(any(), any());
    }

    @Test
    void twoFailuresTwoSuccessesShouldFullyRecover() throws Exception {
        // 对照：两次失败 → 两次成功 → 计数归零 → success=true
        when(llmClientService.chat(any(), any()))
                .thenReturn(reactor.core.publisher.Mono.just(multiplyMultiCallResponse(new String[]{
                        "{\"a\": \"bad1\", \"b\": 2}",       // 失败 → 计数 1
                        "{\"a\": \"bad2\", \"b\": 2}"        // 失败 → 计数 2
                })))
                .thenReturn(reactor.core.publisher.Mono.just(multiplyMultiCallResponse(new String[]{
                        "{\"a\": 3, \"b\": 4}",             // 成功 → 计数 1
                        "{\"a\": 5, \"b\": 6}"               // 成功 → 计数 0（移除）
                })))
                .thenReturn(reactor.core.publisher.Mono.just(finalAnswerResponse()));

        ToolLoopResult result = toolLoopService.run(client, initialMessages());

        assertEquals(true, result.success(), "失败与成功次数相抵 → 记录空 → true");
        verify(llmClientService, times(3)).chat(any(), any());
    }
}
