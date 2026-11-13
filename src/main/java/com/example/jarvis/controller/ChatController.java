package com.example.jarvis.controller;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.service.ChatService;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 对话接口。
 */
@RestController
@RequestMapping("/jarvis")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 对话接口。
     *
     * <p>POST /jarvis/chat</p>
     *
     * @param request 对话请求
     * @return 模型回答
     */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /**
     * 流式对话接口（SSE）。
     *
     * <p>POST /jarvis/chat/stream，响应类型为 {@code text/event-stream}，
     * 模型生成的文本会逐段推送给客户端。</p>
     *
     * @param request 对话请求
     * @return 文本分片流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@Valid @RequestBody ChatRequest request) throws JsonProcessingException {
        return chatService.chatStream(request);
    }

    /**
     * 工具对话接口（非流式，支持 Tool Calling）。
     *
     * <p>POST /jarvis/chat/tools。模型可调用后端 Java 工具（如 multiply），
     * 多轮执行工具直到给出最终答案，一次性返回完整结果。</p>
     *
     * @param request 对话请求
     * @return 模型最终答案
     */
    @PostMapping("/chat/tools")
    public ChatResponse chatWithTools(@Valid @RequestBody ChatRequest request) {
        return chatService.chatWithTools(request);
    }
}
