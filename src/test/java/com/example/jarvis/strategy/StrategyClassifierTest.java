package com.example.jarvis.strategy;

import com.example.jarvis.config.StrategyProperties;
import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import com.example.jarvis.model.IntentType;
import com.example.jarvis.domain.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StrategyClassifier 单元测试（8.2.6）：mock 模型响应，验证"上下文 → 请求组装 →
 * JSON 解析校验 → StrategyResult"链路。真实模型判断质量由联调验证（live test）。
 */
class StrategyClassifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmClientService llmClientService;
    private StrategyClassifier classifier;

    @BeforeEach
    void setUp() throws Exception {
        llmClientService = Mockito.mock(LlmClientService.class);
        LlmClientFactory factory = Mockito.mock(LlmClientFactory.class);
        LlmClientFactory.ResolvedLlmClient client = new LlmClientFactory.ResolvedLlmClient(
                "test-model", "test-model-name", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        Mockito.when(factory.getClient("test-model")).thenReturn(client);

        StrategyProperties properties = new StrategyProperties("test-model");
        classifier = new StrategyClassifier(
                properties, factory, llmClientService, JSON,
                new ClassPathResource("prompts/strategy-classification-prompt.md"));
        classifier.init();
    }

    /**
     * 构造"模型返回指定 content"的响应节点。
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

    private StrategyContext contextOf(String message) {
        return new StrategyContext(message, null, null, List.of());
    }

    // ---- 正常解析 ----

    @Test
    void shouldParsePlannedResult() {
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf(
                        "{\"strategy\": \"PLANNED\", \"reason\": \"第二步依赖第一步的计算结果\"}")));

        StrategyResult result = classifier.classify(contextOf("先计算 128 × 256，再把结果乘以 2"));

        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
        assertTrue(result.reason().contains("依赖"));
    }

    @Test
    void shouldParseDirectResult() {
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf(
                        "{\"strategy\": \"DIRECT\", \"reason\": \"普通知识问答\"}")));

        StrategyResult result = classifier.classify(contextOf("什么是 Redis？"));

        assertEquals(ExecutionStrategy.DIRECT, result.strategy());
    }

    @Test
    void shouldStripCodeFenceBeforeParsing() {
        // 模型违规包 markdown 围栏时仍能解析
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf("""
                        ```json
                        {"strategy": "DIRECT", "reason": "简单问答"}
                        ```
                        """)));

        assertEquals(ExecutionStrategy.DIRECT, classifier.classify(contextOf("你好")).strategy());
    }

    @Test
    void shouldCarryIntentAndHistoryIntoUserContent() {
        // 请求组装验证：user 消息应包含 userMessage / intent / history
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf(
                        "{\"strategy\": \"DIRECT\", \"reason\": \"ok\"}")));

        StrategyContext context = new StrategyContext(
                "查询订单", null, "conv-1",
                List.of(new ChatMessage("user", "上次问了什么")));
        classifier.classify(context);

        var captor = org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
        Mockito.verify(llmClientService).chat(Mockito.any(), captor.capture());
        ObjectNode request = captor.getValue();
        // content 是字符串（序列化的上下文 JSON），asText 解码后断言
        String userContent = request.path("messages").get(1).path("content").asText();

        assertTrue(userContent.contains("\"userMessage\":\"查询订单\"")
                || userContent.contains("\"userMessage\": \"查询订单\""), "应携带 userMessage: " + userContent);
        assertTrue(userContent.contains("上次问了什么"), "应携带 history: " + userContent);
    }

    // ---- 失败处理：明确报错，不默认 DIRECT ----

    @Test
    void shouldRejectUnknownStrategyValue() {
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf(
                        "{\"strategy\": \"MAYBE\", \"reason\": \"x\"}")));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> classifier.classify(contextOf("任意")));
        assertTrue(ex.getMessage().contains("未知策略值"));
    }

    @Test
    void shouldRejectInvalidJson() {
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf("这不是 JSON，模型跑偏了")));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> classifier.classify(contextOf("任意")));
        assertTrue(ex.getMessage().contains("JSON"));
    }

    @Test
    void shouldRejectMissingReason() {
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.just(responseOf(
                        "{\"strategy\": \"DIRECT\"}")));

        assertThrows(IllegalArgumentException.class,
                () -> classifier.classify(contextOf("任意")));
    }

    @Test
    void shouldPropagateModelCallFailure() {
        // 模型调用异常正确传播，不被吞掉
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.error(new RuntimeException("网关超时")));

        RuntimeException ex = assertThrows(
                RuntimeException.class,
                () -> classifier.classify(contextOf("任意")));
        assertEquals("网关超时", ex.getMessage());
    }

    @Test
    void shouldRejectNullResponse() {
        Mockito.when(llmClientService.chat(Mockito.any(), Mockito.any()))
                .thenReturn(Mono.empty());

        assertThrows(IllegalStateException.class,
                () -> classifier.classify(contextOf("任意")));
    }
}
