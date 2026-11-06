package com.example.jarvis.service;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * AI 对话业务：调用大模型生成回答。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    /**
     * 处理一轮对话，返回模型回答。
     *
     * @param request 对话请求
     * @return 包含模型回答的响应
     */
    public ChatResponse chat(ChatRequest request) {
        log.info("收到对话请求: {}", request.getMessage());
        String answer = chatClient.prompt()
                .user(request.getMessage())
                .call()
                .content();
        log.info("模型回答完成, 长度={}", answer == null ? 0 : answer.length());
        return new ChatResponse(answer);
    }
}
