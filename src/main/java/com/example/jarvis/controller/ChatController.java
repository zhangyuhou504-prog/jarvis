package com.example.jarvis.controller;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
