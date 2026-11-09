package com.example.jarvis.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 对话请求体。
 *
 * <p>上游只传用户问题与模板标识；模板变量由 Jarvis 内部注册表维护，
 * 不开放给调用方传入（Prompt 是 Jarvis 的行为配置）。</p>
 */
@Data
public class ChatRequest {

    /**
     * 用户输入的消息。
     */
    @NotBlank(message = "message 不能为空")
    @Size(max = 2000, message = "message 长度不能超过 2000 字符")
    private String message;

    /**
     * 可选：模板标识（如 {@code java-tech}、{@code sql-analysis}）。
     * 不传则使用默认模板。
     */
    private String template;
}
