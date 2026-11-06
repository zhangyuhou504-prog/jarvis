package com.example.jarvis.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话响应体。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /**
     * 模型生成的回答。
     */
    private String answer;
}
