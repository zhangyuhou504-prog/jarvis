package com.example.jarvis.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理：统一异常响应格式，避免把内部堆栈、网关错误等敏感信息暴露给客户端。
 *
 * <p>主要覆盖：参数校验失败（400）、模型/网关调用失败（502）、未知异常（500）。
 * 流式接口响应已提交后的错误无法改状态码，由 {@code Flux} 内部的
 * {@code onErrorResume} 兜底，不经过这里。</p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 请求体参数校验失败（{@code @Valid @RequestBody}）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        Map<String, Object> body = new HashMap<>();
        body.put("error", "请求参数不合法");
        body.put("details", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 方法级约束校验失败（{@code @Validated} 方法参数）。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "请求参数不合法");
        body.put("details", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 模型/网关调用失败。作为代理调用上游模型，上游失败用 502 更贴切。
     */
    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<Map<String, Object>> handleAiService(AiServiceException e) {
        log.error("AI 服务调用失败: {}", e.getMessage(), e);
        Map<String, Object> body = new HashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /**
     * 客户端在响应过程中断开连接（流式接口常见：用户关页面、curl 超时等）。
     *
     * <p>此时响应已提交、客户端已离开，无法也无需再写任何响应。
     * 用 {@code void} 返回（不触发"向 text/event-stream 写 JSON"的 converter 报错），
     * 并降级为 debug 日志，避免正常断连刷屏 ERROR。</p>
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnected(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开连接，停止推送: {}", e.getMessage());
    }

    /**
     * 兜底：未预期异常。绝不暴露堆栈给客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e) {
        log.error("未处理异常", e);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "服务内部错误，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
