package com.example.jarvis.strategy;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 策略分类 Prompt 边界案例实测（8.2.5，真实网关联调）。
 *
 * <p>Purpose: 在写 StrategyClassifier Java 类之前，先验证
 * prompts/strategy-classification-prompt.md 能否让小模型稳定区分
 * DIRECT / PLANNED——重点是三类约束：关键词不误判、意图与策略不耦合、
 * 复杂度不明显默认 DIRECT。</p>
 *
 * <p>本测试是验收性质（@Disabled 默认不跑，验证 Prompt 时手动开启）：
 * 依赖真实网关、消耗真实配额、结果有模型概率性。</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StrategyClassifierPromptLiveTest {

    @Autowired
    private LlmClientService llmClientService;

    @Autowired
    private LlmClientFactory llmClientFactory;

    @Autowired
    private ObjectMapper objectMapper;

    private String prompt() throws Exception {
        try (var in = new ClassPathResource("prompts/strategy-classification-prompt.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String classify(String userMessage) throws Exception {
        LlmClientFactory.ResolvedLlmClient client = llmClientFactory.getClient(null);

        var request = objectMapper.createObjectNode();
        request.put("model", client.modelName());
        request.put("stream", false);
        request.put("temperature", client.temperature());
        request.put("max_tokens", client.maxTokens());
        request.put("enable_thinking", client.enableThinking());

        var messages = request.putArray("messages");
        var system = messages.addObject();
        system.put("role", "system");
        system.put("content", prompt());
        var user = messages.addObject();
        user.put("role", "user");
        user.put("content", userMessage);

        JsonNode response = llmClientService.chat(client, request).block(java.time.Duration.ofSeconds(90));
        assertNotNull(response);
        String content = response.path("choices").path(0).path("message").path("content").asText();
        assertNotNull(content);

        // 剥可能的代码围栏
        String text = content.trim();
        if (text.startsWith("```")) {
            int first = text.indexOf('\n');
            int last = text.lastIndexOf("```");
            if (first > 0 && last > first) {
                text = text.substring(first + 1, last).trim();
            }
        }
        return text;
    }

    private void assertStrategy(String expect, String userMessage) throws Exception {
        String raw = classify(userMessage);
        JsonNode parsed = objectMapper.readTree(raw);
        String strategy = parsed.path("strategy").asText("?");

        // 结果与判定期望对照（失败时把完整输出带出来便于调 Prompt）；
        // 同时写入 UTF-8 结果文件，规避 PowerShell 控制台中文乱码影响判读
        String reason = parsed.path("reason").asText("");
        String line = "[CASE] " + userMessage + " => " + strategy
                + " (期望 " + expect + ") | " + reason;
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("strategy-live-result.txt"), line + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        System.out.println(line);
        assertEquals(expect, strategy, "原始输出: " + raw);
    }

    @Test
    @Order(1)
    void keywordTrapShouldStayDirect() throws Exception {
        // 约束 1：连接词不等于复杂任务（"再"只是表达顺序，无工具依赖）
        assertStrategy("DIRECT", "先介绍 Redis，再介绍 MySQL。");
    }

    @Test
    @Order(2)
    void plainQaShouldStayDirect() throws Exception {
        assertStrategy("DIRECT", "Redis 和 MySQL 有什么区别？");
    }

    @Test
    @Order(3)
    void simpleLogQueryShouldStayDirect() throws Exception {
        assertStrategy("DIRECT", "查询订单服务错误日志。");
    }

    @Test
    @Order(4)
    void multiActionButSimpleShouldStayDirect() throws Exception {
        // 约束 2：多个动作但无依赖 → DIRECT
        assertStrategy("DIRECT", "帮我算一下 3 × 4 和 5 × 6。");
    }

    @Test
    @Order(5)
    void resultPassingShouldPlan() throws Exception {
        // 结果传递（强依赖）→ PLANNED
        assertStrategy("PLANNED", "帮我计算 128 × 256，然后把结果乘以 2。");
    }

    @Test
    @Order(6)
    void crossSourceCorrelationShouldPlan() throws Exception {
        // 跨数据源关联分析 → PLANNED
        assertStrategy("PLANNED", "分析订单服务和支付服务的关联故障：先查询订单服务的错误日志，再根据日志中的订单号查询支付服务的日志，最后做关联分析。");
    }

    @Test
    @Order(7)
    void conditionalExecutionShouldPlan() throws Exception {
        // 条件执行（A 结果决定是否执行 B）→ PLANNED
        assertStrategy("PLANNED", "如果现在已过 21 点，帮我查一下明天的天气，否则告诉我今天是几号。");
    }

    @Test
    @Order(8)
    void singleToolShouldStayDirect() throws Exception {
        assertStrategy("DIRECT", "上海现在几点了？");
    }
}
