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
 *   <li>意图为 GENERAL_QA 且<b>无任何多步文本特征</b> → DIRECT：
 *       意图 + 文本特征联合短路。不能只看意图——实测"帮我计算 128 × 256，
 *       然后把结果乘以 2"会被意图分类成 GENERAL_QA（意图体系无计算类目），
 *       只凭意图短路会放行真正的多步任务</li>
 *   <li>消息中含任务连接词（"然后"、"再"、"接着" 等，正则匹配）→ PLANNED：
 *       连接词暗示多个子任务存在顺序/依赖关系</li>
 *   <li>消息含分隔符特征（1 个及以上分号，或 2 个及以上非空行）→ PLANNED：
 *       用户把任务拆成几段列出来，是最强的复合任务信号</li>
 * </ol>
 *
 * <p><b>GENERAL_QA + 有多步特征时</b>：不短路也不直接判 PLANNED——交给
 * 小模型兜底终审（模型能区分"先介绍 Redis 再介绍 MySQL"的假多步与
 * "先计算再把结果乘 2"的真多步，文本规则做不到这一层）。</p>
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

        // 规则 0（意图 + 文本特征联合判断）：意图为 GENERAL_QA 且消息不含
        // 任何多步文本特征 → 普通知识问答，短路 DIRECT。
        // <p><b>为什么不能只看意图</b>（实测教训）：意图分类器会把"帮我计算
        // 128 × 256，然后把结果乘以 2"识别成 GENERAL_QA（意图体系无"计算"类目，
        // 模型只能就近归类）——若只凭意图短路，多步任务会被直接放行，连接词
        // 规则根本没机会执行。意图只回答"是什么类型"，不回答"有几步"；
        // 文本特征（连接词/分段）是低成本的多步信号，两者联合才可靠。</p>
        // 有文本特征时不短路：交给下方规则或小模型终审（模型能区分
        // "先介绍 Redis 再介绍 MySQL"（DIRECT）与"先计算再把结果乘 2"（PLANNED））
        IntentResult intentResult = context.intentResult();
        if (intentResult != null && intentResult.intent() == IntentType.GENERAL_QA
                && !hasMultiStepTextFeature(message)) {
            log.info("策略规则命中[GENERAL_QA 无多步特征] → DIRECT: conversationId={}",
                    context.conversationId());
            return new StrategyResult(
                    ExecutionStrategy.DIRECT,
                    "意图为普通知识问答，且消息无多步任务特征");
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

    /**
     * 探测消息是否含<b>任何</b>多步任务文本特征（连接词 / 分号 / 多非空行）。
     *
     * <p>供规则 0 使用：GENERAL_QA 短路前先看一眼——有特征说明可能是
     * "披着问答外衣的多步任务"（如"帮我计算 X 然后乘 2"被意图分类为问答），
     * 不能短路，交给小模型终审；无特征才安心短路 DIRECT。</p>
     *
     * @param message 用户消息
     * @return true = 含至少一种多步特征
     */
    private boolean hasMultiStepTextFeature(String message) {
        if (MULTI_TASK_CONNECTORS.matcher(message).find()) {
            return true;
        }
        if (countMatches(SEMICOLON, message) >= 1) {
            return true;
        }
        return message.lines().filter(l -> !l.isBlank()).count() >= MULTI_LINE_THRESHOLD;
    }
}
