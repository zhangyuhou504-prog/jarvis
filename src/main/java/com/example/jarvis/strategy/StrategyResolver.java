package com.example.jarvis.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 执行策略统一入口（第八阶段 8.2.6：Java 规则与小模型兜底整合）。
 *
 * <p><b>只协调，不判断</b>：本类不含任何判断逻辑，只负责把两个判断器
 * 按正确顺序串起来。</p>
 *
 * <table border="1">
 *   <caption>协作关系</caption>
 *   <tr><th>判断器</th><th>职责</th><th>优先级</th></tr>
 *   <tr><td>{@link ExecutionStrategyResolver}</td><td>确定性 Java 规则（意图短路 + 文本特征）</td><td>最高，命中即返回</td></tr>
 *   <tr><td>{@link StrategyClassifier}</td><td>小模型复杂度判断（兜底）</td><td>规则未命中才调用</td></tr>
 * </table>
 *
 * <p><b>三个设计原则</b>：</p>
 * <ol>
 *   <li><b>Java 规则优先级最高</b>：规则已明确判断为 DIRECT / PLANNED，
 *       不再调用小模型（省一次模型调用的延迟与开销）</li>
 *   <li><b>小模型只处理规则无法确定的情况</b>：确定性规则先发挥作用，
 *       概率性判断只做兜底</li>
 *   <li><b>模型异常不能静默变成 DIRECT</b>：{@link StrategyClassifier}
 *       的超时 / JSON 解析失败 / 非法策略值都会抛异常，本类原样传播，
 *       绝不把失败当作"简单任务"处理——否则可能把本应规划的复杂任务直接执行</li>
 * </ol>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class StrategyResolver {

    private final ExecutionStrategyResolver ruleResolver;
    private final StrategyClassifier strategyClassifier;

    /**
     * 统一策略入口：Java 规则优先，未命中交给小模型。
     *
     * @param context 策略判断上下文（用户消息、意图、历史）
     * @return 最终执行策略与原因
     * @throws IllegalStateException    小模型调用失败 / 返回 JSON 不合法（原样传播）
     * @throws IllegalArgumentException 小模型返回未知策略值（原样传播）
     */
    public StrategyResult resolve(StrategyContext context) {

        // 1. 优先使用 Java 规则（确定性判断，零模型开销）
        StrategyResult result = ruleResolver.resolve(context);

        // 2. 规则命中（DIRECT 或 PLANNED）→ 直接返回，不再调模型
        if (result != null) {
            log.info("执行策略由 Java 规则判定: {} → {}（{}）",
                    context.conversationId(), result.strategy(), result.reason());
            return result;
        }

        // 3. 规则无法判断 → 小模型兜底；模型异常原样传播，不伪造策略
        log.info("Java 规则未命中，交给小模型判断: conversationId={}",
                context.conversationId());
        return strategyClassifier.classify(context);
    }
}
