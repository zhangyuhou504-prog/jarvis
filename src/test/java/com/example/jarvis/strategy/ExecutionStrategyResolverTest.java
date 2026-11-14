package com.example.jarvis.strategy;

import com.example.jarvis.model.IntentType;
import com.example.jarvis.model.IntentResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ExecutionStrategyResolver 单元测试（第八阶段 8.2 第 2 项）。
 *
 * <p>只验证 Java 规则判断：命中 → PLANNED（带原因）；
 * 未命中 → null（待小模型兜底）。不测意图参与（那是第 3 项之后的事）。</p>
 */
class ExecutionStrategyResolverTest {

    private final ExecutionStrategyResolver resolver = new ExecutionStrategyResolver();

    private StrategyContext contextOf(String message, IntentType intent) {
        return new StrategyContext(
                message,
                new IntentResult(intent, 0.9, "测试"),
                "conv-test",
                List.of()
        );
    }

    // ---- 命中规则 → PLANNED ----

    // ---- 命中规则 → PLANNED（无意图输入，纯文本规则验证）----
    @Test
    void shouldPlanOnConnectorWord() {
        // 连接词"然后"：多个子任务存在顺序关系
        StrategyResult result = resolver.resolve(contextOf("帮我计算 128 × 256，然后把结果乘以 2", null));
        assertNotNull(result);
        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
    }

    @Test
    void shouldPlanOnOtherConnectorWords() {
        // 每个连接词单独验证（正则覆盖完整性）
        for (String msg : List.of("先查时间，再算结果", "接着上一步继续", "其次处理数据",
                "最后总结一下", "之后再检查", "查询并且分析", "同时统计两个指标", "汇总以及导出")) {
            StrategyResult result = resolver.resolve(contextOf(msg, null));
            assertNotNull(result, "应命中连接词规则: " + msg);
            assertEquals(ExecutionStrategy.PLANNED, result.strategy());
        }
    }

    @Test
    void shouldPlanOnSemicolonSeparatedInstructions() {
        // 两个分号分隔三条指令（达到门槛 2）
        StrategyResult result = resolver.resolve(contextOf("查一下今天的日期;算 2 的 10 次方;告诉我现在几点", null));
        assertNotNull(result);
        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
    }

    @Test
    void shouldPlanOnMultiLineInstructions() {
        // 两个非空行（达到门槛 2）
        StrategyResult result = resolver.resolve(contextOf("帮我查一下上海现在几点\n再把结果乘以 100", null));
        assertNotNull(result);
        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
    }

    // ---- 未命中 → null（待小模型兜底）----

    @Test
    void shouldReturnNullOnSimpleQuestion() {
        // 单步问答：无连接词、无分隔符、单行
        assertNull(resolver.resolve(contextOf("现在几点了？", null)));
    }

    @Test
    void shouldReturnNullOnSingleToolCall() {
        // 单步工具调用（一句话一个任务）
        assertNull(resolver.resolve(contextOf("帮我算一下 128 乘以 256", null)));
    }

    @Test
    void shouldPlanOnSingleSemicolon() {
        // 单个分号也算显式分段（验收表：查询订单；查询支付记录 → PLANNED）
        StrategyResult result = resolver.resolve(contextOf("这是一个包含分号的普通问题;仅此而已", null));
        assertNotNull(result);
        assertEquals(ExecutionStrategy.PLANNED, result.strategy());
    }

    @Test
    void shouldReturnNullOnBlankLinesOnly() {
        // 多行但只有一个非空行：空行不算任务段
        assertNull(resolver.resolve(contextOf("只有一行内容\n\n\n", null)), "空行不算任务段");
    }

    // ---- 验收用例（8.2 第 2 项：意图短路 + 文本规则）----

    @Test
    void acceptanceWhatIsRedis() {
        // 意图 GENERAL_QA → DIRECT
        StrategyResult r = resolver.resolve(contextOf("什么是 Redis？", IntentType.GENERAL_QA));
        assertNotNull(r);
        assertEquals(ExecutionStrategy.DIRECT, r.strategy());
    }

    @Test
    void acceptanceQueryOrderThenPayment() {
        // DATA_QUERY + 连接词 → PLANNED
        StrategyResult r = resolver.resolve(contextOf("查询订单，然后查询支付记录", IntentType.DATA_QUERY));
        assertNotNull(r);
        assertEquals(ExecutionStrategy.PLANNED, r.strategy());
    }

    @Test
    void acceptanceSemicolonSeparatedQueries() {
        // DATA_QUERY + 双分号分隔 → PLANNED
        StrategyResult r = resolver.resolve(contextOf("查询订单；查询支付记录", IntentType.DATA_QUERY));
        assertNotNull(r);
        assertEquals(ExecutionStrategy.PLANNED, r.strategy());
    }

    @Test
    void acceptanceSqlAnalysisUnresolved() {
        // SQL_ANALYSIS 单步 → 未确定（null，待小模型兜底）
        assertNull(resolver.resolve(contextOf("分析一下这条 SQL", IntentType.SQL_ANALYSIS)));
    }

    @Test
    void acceptanceTroubleshootingUnresolved() {
        // TROUBLESHOOTING 单步 → 未确定（null，待小模型兜底）
        assertNull(resolver.resolve(contextOf("查询订单服务最近 30 分钟的错误日志", IntentType.TROUBLESHOOTING)));
    }

    @Test
    void acceptanceRedisVsMysql() {
        // GENERAL_QA + 句式含"再/然后"类词 → 仍 DIRECT（意图优先于文本特征）
        StrategyResult r = resolver.resolve(contextOf("Redis 和 MySQL 有什么区别？", IntentType.GENERAL_QA));
        assertNotNull(r);
        assertEquals(ExecutionStrategy.DIRECT, r.strategy());
    }

    // ---- 修复回归：GENERAL_QA + 多步文本特征 → 不短路，交给小模型 ----

    @Test
    void generalQaWithConnectorShouldNotShortCircuit() {
        // 实测教训：意图分类器把"计算 X 然后乘 2"归类为 GENERAL_QA（无计算类目）。
        // 修复后：有连接词特征时不短路 → 规则 1 判 PLANNED（连接词规则接管）
        StrategyResult r = resolver.resolve(
                contextOf("帮我计算 128 × 256，然后把结果乘以 2", IntentType.GENERAL_QA));
        assertNotNull(r, "GENERAL_QA + 连接词特征不能被意图短路放行");
        assertEquals(ExecutionStrategy.PLANNED, r.strategy());
    }

    @Test
    void generalQaWithMultiLineShouldNotShortCircuit() {
        // GENERAL_QA + 多行指令 → 不短路（多行特征触发）
        StrategyResult r = resolver.resolve(
                contextOf("查询订单服务日志\n查询支付服务日志", IntentType.GENERAL_QA));
        assertNotNull(r);
        assertEquals(ExecutionStrategy.PLANNED, r.strategy());
    }

    @Test
    void generalQaWithoutFeatureShouldStillShortCircuit() {
        // 无特征的纯问答仍短路 DIRECT（不多花小模型调用）——短路收益保留
        StrategyResult r = resolver.resolve(contextOf("什么是 Redis？", IntentType.GENERAL_QA));
        assertNotNull(r);
        assertEquals(ExecutionStrategy.DIRECT, r.strategy());
    }

    // ---- 数据结构不变量 ----

    @Test
    void shouldRejectBlankUserMessage() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new StrategyContext("  ", null, null, null));
    }

    @Test
    void shouldTreatNullHistoryAsEmpty() {
        // history 为 null → 安全转为空列表（不抛异常）
        StrategyContext context = new StrategyContext("现在几点了", null, null, null);
        assertEquals(List.of(), context.history());
    }

    @Test
    void shouldReturnNullWhenIntentUnknownOrAbsent() {
        // 意图为 UNKNOWN 或 null（识别关闭/失败）→ 不短路，走文本规则
        assertNull(resolver.resolve(contextOf("分析一下这条 SQL", IntentType.UNKNOWN)));
        assertNull(resolver.resolve(contextOf("分析一下这条 SQL", null)));
    }
}
