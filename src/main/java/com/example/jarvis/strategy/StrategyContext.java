package com.example.jarvis.strategy;

import com.example.jarvis.domain.ChatMessage;
import com.example.jarvis.model.IntentResult;

import java.util.List;

/**
 * 执行策略判断的输入上下文（第八阶段 8.2）。
 *
 * <p>纯数据载体：把"判断执行策略可能需要的信息"打包带齐，
 * 不含任何判断逻辑（判断是 {@link ExecutionStrategyResolver} 的职责）。</p>
 *
 * <p><b>各字段在判断中的角色</b>：</p>
 * <ul>
 *   <li>{@code userMessage}：Java 规则判断的主要依据（连接词、长度）</li>
 *   <li>{@code intentResult}：当前消息的意图识别结果。Java 规则第一版
 *       未使用，预留给小模型兜底判断（8.2 第 3 项）作为输入特征</li>
 *   <li>{@code conversationId} / {@code history}：会话上下文。同样预留给
 *       兜底判断（如"接着说"这类依赖上下文才能理解的指代）</li>
 * </ul>
 *
 * @param userMessage    用户本次消息（非空，判断的主要依据）
 * @param intentResult   意图识别结果（可为 null：意图识别关闭或识别失败时）
 * @param conversationId 会话 ID（可为 null：无会话的裸请求）
 * @param history        会话历史消息（null 安全，转为不可变列表）
 */
public record StrategyContext(
        String userMessage,
        IntentResult intentResult,
        String conversationId,
        List<ChatMessage> history
) {
    public StrategyContext {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage 不能为空");
        }
        history = history == null ? List.of() : List.copyOf(history);
    }
}
