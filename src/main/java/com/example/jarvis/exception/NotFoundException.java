package com.example.jarvis.exception;

/**
 * 资源不存在异常：会话 ID 等标识查不到对应资源时抛出，由全局异常处理转 404。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
