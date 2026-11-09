package com.example.jarvis.exception;

/**
 * AI 服务调用异常：模型/网关调用失败时抛出。
 *
 * <p>只携带面向用户的友好提示；内部细节（异常堆栈、网关原始错误）由日志记录，
 * 不随响应暴露给客户端。</p>
 */
public class AiServiceException extends RuntimeException {

    public AiServiceException(String message) {
        super(message);
    }

    public AiServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
