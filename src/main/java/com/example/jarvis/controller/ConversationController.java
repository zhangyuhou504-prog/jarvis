package com.example.jarvis.controller;

import com.example.jarvis.dto.ConversationCreateResponse;
import com.example.jarvis.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理接口。
 *
 * <ul>
 *   <li>POST   /jarvis/conversations          创建会话，返回 conversationId</li>
 *   <li>DELETE /jarvis/conversations/{id}     清空会话历史</li>
 * </ul>
 */
@RestController
@RequestMapping("/jarvis/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    /**
     * 创建新会话。
     *
     * @return 包含后端生成的 conversationId 的响应
     */
    @PostMapping
    public ConversationCreateResponse create() {
        return new ConversationCreateResponse(conversationService.createConversation().getConversationId());
    }

    /**
     * 清空指定会话的消息历史。
     *
     * @param conversationId 会话标识
     */
    @DeleteMapping("/{conversationId}")
    public void clear(@PathVariable String conversationId) {
        conversationService.clearConversation(conversationId);
    }
}
