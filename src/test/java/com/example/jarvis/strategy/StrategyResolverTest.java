package com.example.jarvis.strategy;

import com.example.jarvis.model.IntentResult;
import com.example.jarvis.model.IntentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StrategyResolver 整合测试（8.2.6 第二个任务）。
 *
 * <p>四组验收场景（Mockito 验证模型调用次数）：规则命中不调模型、
 * 规则未命中调模型一次、模型异常原样传播不伪造策略。</p>
 */
class StrategyResolverTest {

    private ExecutionStrategyResolver ruleResolver;
    private StrategyClassifier strategyClassifier;
    private StrategyResolver strategyResolver;

    @BeforeEach
    void setUp() {
        ruleResolver = mock(ExecutionStrategyResolver.class);
        strategyClassifier = mock(StrategyClassifier.class);
        strategyResolver = new StrategyResolver(ruleResolver, strategyClassifier);
    }

    private StrategyContext contextOf(String message) {
        return new StrategyContext(message,
                new IntentResult(IntentType.GENERAL_QA, 0.9, "测试"),
                "conv-test", List.of());
    }

    // ---- 场景 1：Java 规则命中 DIRECT → 返回规则结果，不调用模型 ----

    @Test
    void shouldReturnRuleDirectWithoutCallingModel() {
        when(ruleResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.DIRECT, "普通知识问答，无需任务规划"));

        StrategyResult result = strategyResolver.resolve(contextOf("什么是 Redis？"));

        assertEquals(ExecutionStrategy.DIRECT, result.strategy());
        assertEquals("普通知识问答，无需任务规划", result.reason());
        // 规则已命中：小模型绝不能被调用
        verify(strategyClassifier, never()).classify(any());
    }

    // ---- 场景 2：Java 规则命中 PLANNED → 返回规则结果，不调用模型 ----

    @Test
    void shouldReturnRulePlannedWithoutCallingModel() {
        when(ruleResolver.resolve(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.PLANNED, "消息包含任务连接词"));

        StrategyResult result = strategyResolver.resolve(contextOf("查询订单，然后查询支付记录"));

        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
        assertEquals("消息包含任务连接词", result.reason());
        verify(strategyClassifier, never()).classify(any());
    }

    // ---- 场景 3：Java 规则返回 null → 调用 StrategyClassifier（恰好一次） ----

    @Test
    void shouldCallClassifierWhenRuleUnresolved() {
        when(ruleResolver.resolve(any())).thenReturn(null);
        when(strategyClassifier.classify(any()))
                .thenReturn(new StrategyResult(ExecutionStrategy.PLANNED, "第二步依赖第一步结果"));

        StrategyResult result = strategyResolver.resolve(contextOf("分析一下这条 SQL"));

        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
        // 规则未命中：小模型恰好被调用一次
        verify(strategyClassifier, times(1)).classify(any());
    }

    // ---- 场景 4：小模型调用失败 → 异常明确传播，不伪造策略 ----

    @Test
    void shouldPropagateClassifierFailureWithoutFakingStrategy() {
        when(ruleResolver.resolve(any())).thenReturn(null);
        when(strategyClassifier.classify(any()))
                .thenThrow(new IllegalStateException("策略判断模型调用没有返回结果"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> strategyResolver.resolve(contextOf("任意消息")));

        assertTrue(ex.getMessage().contains("没有返回结果"));
        // 异常路径也不会二次调用模型
        verify(strategyClassifier, times(1)).classify(any());
    }

    // ---- 边界补充：两条链路都不会被绕过 ----

    @Test
    void ruleResultShouldPassThroughUntouched() {
        // 整合层不得篡改规则结果（策略与原因原样透传）
        StrategyResult ruleResult = new StrategyResult(ExecutionStrategy.DIRECT, "原因原文");
        when(ruleResolver.resolve(any())).thenReturn(ruleResult);

        StrategyResult result = strategyResolver.resolve(contextOf("任意"));

        assertEquals(ruleResult, result);
    }

    @Test
    void classifierResultShouldPassThroughUntouched() {
        StrategyResult classifierResult = new StrategyResult(ExecutionStrategy.PLANNED, "模型原因原文");
        when(ruleResolver.resolve(any())).thenReturn(null);
        when(strategyClassifier.classify(any())).thenReturn(classifierResult);

        assertEquals(classifierResult, strategyResolver.resolve(contextOf("任意")));
        // 各判断器恰好被调用一次，无多余交互
        verify(ruleResolver, times(1)).resolve(any());
        verify(strategyClassifier, times(1)).classify(any());
    }
}
