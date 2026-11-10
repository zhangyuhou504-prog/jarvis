package com.example.jarvis.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建会话响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConversationCreateResponse {

    /**
     * 后端生成的会话标识，后续聊天请求需携带。
     */
    private String conversationId;
}
