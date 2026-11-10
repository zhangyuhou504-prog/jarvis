package com.example.jarvis.controller;

import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.service.ExperimentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 网关兼容性对照实验接口（临时，验证后删除）。
 *
 * <ul>
 *   <li>POST /jarvis/experiment/spring-ai    实验 A：Spring AI 2.0.2 流式</li>
 *   <li>POST /jarvis/experiment/langchain4j  实验 B：LangChain4j 流式</li>
 * </ul>
 *
 * <p>输出：内容分片为默认事件；{@code event: done} 正常结束；{@code event: error} 出错（含异常类名）。</p>
 */
@RestController
@RequestMapping("/jarvis/experiment")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;

    /**
     * 实验 A：Spring AI 2.0.2 流式。
     */
    @PostMapping(value = "/spring-ai", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> springAi(@Valid @RequestBody ChatRequest request) {
        return experimentService.springAiStream(request);
    }

    /**
     * 实验 B：LangChain4j 流式。
     */
    @PostMapping(value = "/langchain4j", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> langChain4j(@Valid @RequestBody ChatRequest request) {
        return experimentService.langChain4jStream(request);
    }
}
