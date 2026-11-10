package com.example.jarvis.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话：一次独立的多轮对话。
 *
 * <p>每个会话由后端生成的 {@code conversationId} 标识，
 * 持有按时间顺序排列的消息历史（user / assistant 交替）。</p>
 *
 * <p>注意：{@code messages} 目前是普通 {@link ArrayList}，
 * 同一会话并发追加时暂不安全，第二部分接入聊天流程时统一处理。</p>
 */
@Data
public class Conversation {

    /**
     * 会话标识（后端生成的 UUID）。
     */
    private final String conversationId;

    /**
     * 会话消息历史（按时间顺序）。
     */
    private final List<ChatMessage> messages = new ArrayList<>();

    public Conversation(String conversationId) {
        this.conversationId = conversationId;
    }
}
