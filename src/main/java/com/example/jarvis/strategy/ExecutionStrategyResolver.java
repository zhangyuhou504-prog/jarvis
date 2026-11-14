package com.example.jarvis.strategy;

import com.example.jarvis.model.IntentResult;
import com.example.jarvis.model.IntentType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 执行策略解析器（第八阶段 8.2 第 2 项：Java 规则判断）。
 *
 * <p><b>职责</b>：输入 {@link StrategyContext}，用确定性 Java 规则判断本次请求
 * 走 {@link ExecutionStrategy#DIRECT}（直接对话/单步工具）还是
 * {@link ExecutionStrategy#PLANNED}（规划执行链路）。判断不明确时返回 null，
 * 交给小模型兜底（8.2 第 3 项，本类不负责）。</p>
 *
 * <p><b>为什么先做 Java 规则</b>：确定性规则快（微秒级）、零成本、可解释、
 * 行为可预测。小模型判断有延迟（秒级）和不确定性，只做规则覆盖不到的兜底。
 * 顺序是规则先行，模型兜底，与意图路由同一个思路。</p>
 *
 * <p><b>当前规则集（第一版，按判断顺序）</p>
 * <ol>
 *   <li>意图为 GENERAL_QA（普通知识问答）→ DIRECT：<b>短路最前</b>。
 *       普通问答不应因为句中出现"然后""再"等词就被误判进 Planner
 *       （如"Redis 和 MySQL 有什么区别？"）——意图信号优先于文本特征</li>
 *   <li>消息中含任务连接词（"然后"、"再"、"接着" 等，正则匹配）→ PLANNED：
 *       连接词暗示多个子任务存在顺序/依赖关系</li>
 *   <li>消息含分隔符特征（两个及以上分号，或两个及以上非空行）→ PLANNED：
 *       用户把任务拆成几段列出来，是最强的复合任务信号</li>
 * </ol>
 *
 * <p>三条规则都不命中 → 返回 null（不明确），由外层走小模型兜底。</p>
 */
@Slf4j
@Component
public class ExecutionStrategyResolver {

    /**
     * 任务连接词正则：命中说明消息里串联了多个子任务。
     */
    private static final Pattern MULTI_TASK_CONNECTORS = Pattern.compile(
            "然后|再|接着|其次|最后|之后|并且|同时|以及");

    /**
     * 分号分隔特征：分号是用户切分多条指令的显式标记。
     */
    private static final Pattern SEMICOLON = Pattern.compile("[;；]");

    /**
     * 多行指令的非空行数门槛（达到才算多段任务）。
     */
    private static final int MULTI_LINE_THRESHOLD = 2;

    /**
     * 用 Java 规则判断执行策略。
     *
     * @param context 策略判断上下文（含用户消息等）
     * @return 判断结果（含策略与原因）；规则无法确定时返回 null，
     *         表示需要小模型兜底判断
     */
    public StrategyResult resolve(StrategyContext context) {
        String message = context.userMessage();

        // 规则 0（短路最前）：意图为 GENERAL_QA → 普通知识问答，无需规划。
        // 必须放在文本规则之前：普通问答不应因为句中出现"然后""再"等词
        // 就被误判进 Planner（如"Redis 和 MySQL 有什么区别？"里没有连接词，
        // 但"先说相同点，再说区别"这类句式有——意图信号优先于文本特征）
        IntentResult intentResult = context.intentResult();
        if (intentResult != null && intentResult.intent() == IntentType.GENERAL_QA) {
            log.info("策略规则命中[GENERAL_QA] → DIRECT: conversationId={}",
                    context.conversationId());
            return new StrategyResult(
                    ExecutionStrategy.DIRECT,
                    "意图为普通知识问答，无需任务规划");
        }

        // 规则 1：任务连接词 → 多个子任务存在顺序关系
        if (MULTI_TASK_CONNECTORS.matcher(message).find()) {
            log.info("策略规则命中[连接词] → PLANNED: conversationId={}",
                    context.conversationId());
            return new StrategyResult(
                    ExecutionStrategy.PLANNED,
                    "消息包含任务连接词（然后/再/接着等），疑似多步任务");
        }

        // 规则 2a：分号分隔的多条指令（1 个即算：用户用分号显式切段，
        // 是主动拆分任务的强信号；单分号在普通陈述句中很少见，误伤率低）
        if (countMatches(SEMICOLON, message) >= 1) {
            log.info("策略规则命中[分号] → PLANNED: conversationId={}",
                    context.conversationId());
            return new StrategyResult(
                    ExecutionStrategy.PLANNED,
                    "消息含分号分隔的多条指令，疑似多段任务");
        }

        // 规则 2b：多行非空行指令
        long nonBlankLines = message.lines().filter(l -> !l.isBlank()).count();
        if (nonBlankLines >= MULTI_LINE_THRESHOLD) {
            log.info("策略规则命中[多行指令] → PLANNED: conversationId={}",
                    context.conversationId());
            return new StrategyResult(
                    ExecutionStrategy.PLANNED,
                    "消息为多行指令（" + nonBlankLines + " 行），疑似多段任务");
        }

        // 不明确 → 返回 null，交给小模型兜底（8.2 第 3 项）
        log.debug("Java 规则未能确定执行策略，需小模型兜底: conversationId={}",
                context.conversationId());
        return null;
    }

    /**
     * 统计字符串中某正则的命中次数。
     */
    private int countMatches(Pattern pattern, String input) {
        return (int) pattern.matcher(input).results().count();
    }
}
