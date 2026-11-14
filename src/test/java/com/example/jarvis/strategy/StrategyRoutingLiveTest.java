package com.example.jarvis.strategy;

import com.example.jarvis.model.IntentResult;
import com.example.jarvis.service.IntentService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 用户实测两条提示词的端到端链路验证（8.2.7 接入后）：
 * 意图识别（真实网关）→ 策略判定（规则/小模型）→ 预期分流。
 *
 * <p>验证问题：这两条消息意图是否 UNKNOWN？UNKNOWN 时策略判定是否仍正确？</p>
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StrategyRoutingLiveTest {

    @Autowired
    private IntentService intentService;

    @Autowired
    private StrategyResolver strategyResolver;

    private void verify(String message, ExecutionStrategy expect) throws Exception {
        // 1. 意图识别（真实网关）
        IntentResult intent = intentService.recognize(message);

        // 2. 策略判定（Java 规则优先，未命中走小模型）
        StrategyResult strategy = strategyResolver.resolve(new StrategyContext(
                message, intent, null, List.of()));

        String line = "[VERIFY] 消息=" + message
                + " | 意图=" + intent.intent()
                + " | 策略=" + strategy.strategy()
                + " (期望 " + expect + ")"
                + " | 策略原因=" + strategy.reason();
        System.out.println(line);
        Files.writeString(Path.of("strategy-routing-live.txt"), line + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);

        assertNotNull(strategy);
        assertEquals(expect, strategy.strategy(),
                "意图=" + intent.intent() + ", 原因=" + strategy.reason());
    }

    @Test
    @Order(1)
    void calcThenMultiplyShouldPlan() throws Exception {
        verify("帮我计算 128 × 256，然后把结果乘以 2", ExecutionStrategy.PLANNED);
    }

    @Test
    @Order(2)
    void conditionalWeatherShouldPlan() throws Exception {
        verify("如果现在已过 21 点，帮我查一下明天的天气，否则告诉我今天是几号", ExecutionStrategy.PLANNED);
    }
}
