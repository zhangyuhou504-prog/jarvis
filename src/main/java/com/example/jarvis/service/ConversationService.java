package com.example.jarvis.service;

import com.example.jarvis.domain.Conversation;
import com.example.jarvis.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话服务：会话的创建、查询与清空。
 *
 * <p>本阶段使用进程内存存储（{@link ConcurrentHashMap}），服务重启后会话丢失、
 * 多实例部署不共享——后续升级为 Redis / 数据库。</p>
 *
 * <p>线程安全说明：{@code conversations} 用 {@link ConcurrentHashMap} 保证
 * 会话映射本身的并发安全；但单个会话内 {@code messages} 的并发修改
 * 尚未处理，第二部分接入聊天流程时统一加锁。</p>
 */
@Service
@Slf4j
public class ConversationService {

    /**
     * 会话存储：conversationId → Conversation。
     */
    private final ConcurrentMap<String, Conversation> conversations = new ConcurrentHashMap<>();

    /**
     * 创建新会话，由后端生成会话 ID。
     *
     * @return 新建的会话
     */
    public Conversation createConversation() {
        String conversationId = UUID.randomUUID().toString();
        Conversation conversation = new Conversation(conversationId);
        conversations.put(conversationId, conversation);
        log.info("创建会话: {}", conversationId);
        return conversation;
    }

    /**
     * 按 ID 查询会话。
     *
     * @param conversationId 会话标识
     * @return 对应会话
     * @throws NotFoundException 会话不存在
     */
    public Conversation getConversation(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            throw new NotFoundException("会话不存在: " + conversationId);
        }
        return conversation;
    }

    /**
     * 清空会话的消息历史（会话本身保留，可继续对话）。
     *
     * @param conversationId 会话标识
     * @throws NotFoundException 会话不存在
     */
    public void clearConversation(String conversationId) {
        Conversation conversation = getConversation(conversationId);
        conversation.getMessages().clear();
        log.info("清空会话历史: {}", conversationId);
    }
}
