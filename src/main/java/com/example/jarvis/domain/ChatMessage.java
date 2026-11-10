package com.example.jarvis.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话消息：一次会话中的一条消息。
 *
 * <p>{@code role} 取值：{@code user}（用户输入）、{@code assistant}（模型回答）。
 * system 提示词不属于会话历史，由模板渲染后单独拼接，不存入会话。</p>
 *
 * <p>当前只保留 role/content 两个字段；后续需要时可扩展
 * 创建时间、Token 统计、工具调用记录等。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /**
     * 消息角色：user / assistant。
     */
    private String role;

    /**
     * 消息内容。
     */
    private String content;
}
