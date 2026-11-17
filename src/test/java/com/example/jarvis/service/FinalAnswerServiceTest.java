package com.example.jarvis.service;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FinalAnswerService 单元测试（8.6.3 收尾）。
 *
 * <p>mock LlmClientService 模拟模型响应，验证：</p>
 * <ul>
 *   <li>消息结构：system（最终答案 Prompt）+ user（两段式：需求 + 执行记录）</li>
 *   <li>模型解析：modelId 原样传给 getClient（null 回落由工厂处理）</li>
 *   <li>content 提取与空内容拒绝</li>
 * </ul>
 */
class FinalAnswerServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmClientService llmClientService;
    private LlmClientFactory factory;
    private FinalAnswerService finalAnswerService;
    private LlmClientFactory.ResolvedLlmClient client;

    @BeforeEach
    void setUp() {
        llmClientService = mock(LlmClientService.class);
        factory = mock(LlmClientFactory.class);
        client = new LlmClientFactory.ResolvedLlmClient(
                "mxbc-qwen", "qwen3.8-27b", 0.7, 4096, false,
                "http://localhost", WebClient.create());
        when(factory.getClient("mxbc-qwen")).thenReturn(client);
        when(factory.getClient(null)).thenReturn(client);

        finalAnswerService = new FinalAnswerService(
                llmClientService, factory, JSON,
                new ClassPathResource("prompts/final-answer-prompt.md"));
    }

    /**
     * 构造"模型返回指定 content"的响应。
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

    @Test
    void shouldBuildSystemAndTwoPartUserMessage() {
        // 消息结构：system=最终答案 Prompt；user=两段式（需求 + 执行记录）
        when(llmClientService.chat(eq(client), any()))
                .thenReturn(Mono.just(responseOf("现在 19:25，小时大于 12。")));

        finalAnswerService.generate(
                "获取当前时间，如果小时大于12就返回当前时间，否则计算7×8。",
                "整体状态：全部成功\n\n[step_1] SUCCESS\n任务：获取当前时间\n结果：19:25",
                "mxbc-qwen");

        ArgumentCaptor<ObjectNode> captor = ArgumentCaptor.forClass(ObjectNode.class);
        verify(llmClientService, times(1)).chat(eq(client), captor.capture());
        ObjectNode request = captor.getValue();

        // 请求基础字段
        assertEquals("qwen3.8-27b", request.path("model").asText());
        assertEquals(false, request.path("stream").asBoolean());

        // system 消息：最终答案 Prompt（含角色定位关键词）
        assertEquals(2, request.path("messages").size());
        assertEquals("system", request.path("messages").get(0).path("role").asText());
        assertTrue(request.path("messages").get(0).path("content").asText()
                .contains("最终答案生成器"));

        // user 消息：两段式 content（必须是字符串，OpenAI 协议约束）
        assertEquals("user", request.path("messages").get(1).path("role").asText());
        String userContent = request.path("messages").get(1).path("content").asText();
        assertTrue(userContent.startsWith("用户原始需求："));
        assertTrue(userContent.contains("如果小时大于12"));
        assertTrue(userContent.contains("\n\n任务执行记录："));
        assertTrue(userContent.contains("[step_1] SUCCESS"));
    }

    @Test
    void shouldResolveClientWithGivenModelId() {
        // modelId 原样传给工厂解析（模型选择复用 LlmClientFactory 体系）
        when(llmClientService.chat(eq(client), any()))
                .thenReturn(Mono.just(responseOf("最终结果 65536。")));

        finalAnswerService.generate("帮我算一下", "整体状态：全部成功", "mxbc-qwen");

        verify(factory, times(1)).getClient("mxbc-qwen");
        // null 也交给工厂回落默认模型（服务层不做模型名硬编码）
        finalAnswerService.generate("帮我算一下", "整体状态：全部成功", null);
        verify(factory, times(1)).getClient(null);
    }

    @Test
    void shouldReturnModelContentAsFinalAnswer() {
        when(llmClientService.chat(eq(client), any()))
                .thenReturn(Mono.just(responseOf("128 × 256 = 32768，再乘以 2，最终结果是 65536。")));

        String answer = finalAnswerService.generate(
                "帮我计算 128 × 256，然后把结果乘以 2",
                "整体状态：全部成功",
                null);

        assertEquals("128 × 256 = 32768，再乘以 2，最终结果是 65536。", answer);
    }

    @Test
    void shouldRejectBlankModelContent() {
        // 模型无返回/空内容 → 明确异常，不静默返回空串（由调用方决定兜底策略）
        when(llmClientService.chat(eq(client), any()))
                .thenReturn(Mono.just(responseOf("")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> finalAnswerService.generate("任务", "报告", null));
        assertTrue(ex.getMessage().contains("没有返回有效内容"));
    }

    @Test
    void shouldRejectInvalidInput() {
        // userMessage：null / 空白拒绝
        assertThrows(IllegalArgumentException.class,
                () -> finalAnswerService.generate(null, "报告", null));
        assertThrows(IllegalArgumentException.class,
                () -> finalAnswerService.generate("  ", "报告", null));

        // executionReport：null / 空白拒绝
        assertThrows(IllegalArgumentException.class,
                () -> finalAnswerService.generate("任务", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> finalAnswerService.generate("任务", " ", null));
    }
}
