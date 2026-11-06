package com.example.jarvis.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 对话请求体。
 */
@Data
public class ChatRequest {

    /**
     * 用户输入的消息。
     */
    @NotBlank(message = "message 不能为空")
    private String message;
}
