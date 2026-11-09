package com.example.jarvis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
    @Size(max = 2000, message = "message 长度不能超过 2000 字符")
    private String message;
}
