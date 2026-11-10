package com.example.jarvis.service;

import com.example.jarvis.dto.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关兼容性对照实验服务（临时）。
 *
 * <p>实验 A：纯 Spring AI 2.0.2（底层 OpenAI 官方 Java SDK，严格校验 chunk 字段）。<br>
 * 实验 B：LangChain4j 1.23.0（自有 OpenAI 实现，自有 Jackson 反序列化）。<br>
 * 两者用同一网关、同一模型、同样的消息与参数，验证"缺 id 的 chunk"是否都报错。</p>
 *
 * <p>注意：LangChain4j 的 {@code ChatRequest}/{@code UserMessage} 与 Spring AI 同名，
 * 这里统一用全限定名引用，避免 import 冲突。</p>
 *
 * <p>输出约定：内容分片为默认事件；正常结束发 {@code event: done}；
 * 出错发 {@code event: error}（含异常类名与消息），便于 curl 直接对比。</p>
 */
@Service
@Slf4j
public class ExperimentService {

    private final ChatModel springAiChatModel;
    private final OpenAiStreamingChatModel langChain4jModel;
    private final String model;

    public ExperimentService(ChatModel springAiChatModel,
                             @Value("${spring.ai.openai.base-url}") String baseUrl,
                             @Value("${spring.ai.openai.api-key:}") String apiKey,
                             @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.springAiChatModel = springAiChatModel;
        this.model = model;
        this.langChain4jModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    /**
     * 实验 A：Spring AI 2.0.2 流式（裸 {@code ChatModel.stream}，与当初出事的链路一致）。
     *
     * @param request 对话请求
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> springAiStream(ChatRequest request) {
        log.info("[实验A] Spring AI 流式请求: {}", request.getMessage());
        AtomicInteger chunks = new AtomicInteger();
        // extraBody 透传 enable_thinking=false，与实验 B 参数对齐；model 必须显式设置，
        // 否则会覆盖自动装配的默认模型导致网关 404
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .extraBody(Map.of("enable_thinking", false))
                .build();

        return springAiChatModel.stream(new Prompt(List.of(new UserMessage(request.getMessage())), options))
                .map(resp -> resp == null || resp.getResult() == null ? null : resp.getResult().getOutput().getText())
                .filter(Objects::nonNull)
                .filter(text -> !text.isEmpty())
                .doOnNext(text -> chunks.incrementAndGet())
                .map(text -> ServerSentEvent.<String>builder().data(text).build())
                .doOnComplete(() -> log.info("[实验A] 完成, chunk数={}", chunks.get()))
                .concatWith(Flux.defer(() -> Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("[complete] chunk数=" + chunks.get())
                                .build())))
                .doOnError(err -> log.error("[实验A] 出错: {}", err.getMessage(), err))
                .onErrorResume(err -> Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("error")
                                .data("[error] " + err.getClass().getSimpleName() + ": " + err.getMessage())
                                .build()));
    }

    /**
     * 实验 B：LangChain4j 流式（自有 OpenAI 实现）。
     *
     * @param request 对话请求
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<String>> langChain4jStream(ChatRequest request) {
        log.info("[实验B] LangChain4j 流式请求: {}", request.getMessage());
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicInteger chunks = new AtomicInteger();

        // customParameters 透传 enable_thinking=false，与实验 A 参数对齐
        ChatRequestParameters params = OpenAiChatRequestParameters.builder()
                .customParameters(Map.of("enable_thinking", false))
                .build();
        dev.langchain4j.model.chat.request.ChatRequest lcRequest =
                dev.langchain4j.model.chat.request.ChatRequest.builder()
                        .messages(new dev.langchain4j.data.message.UserMessage(request.getMessage()))
                        .parameters(params)
                        .build();

        langChain4jModel.chat(lcRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                if (token != null && !token.isEmpty()) {
                    chunks.incrementAndGet();
                    sink.tryEmitNext(ServerSentEvent.<String>builder().data(token).build());
                }
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                String full = response == null || response.aiMessage() == null
                        ? "" : response.aiMessage().text();
                log.info("[实验B] 完成, chunk数={}, 总长={}", chunks.get(), full.length());
                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("[complete] chunk数=" + chunks.get() + ", 总长=" + full.length())
                        .build());
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("[实验B] 出错: {}", error.getMessage(), error);
                sink.tryEmitNext(ServerSentEvent.<String>builder()
                        .event("error")
                        .data("[error] " + error.getClass().getSimpleName() + ": " + error.getMessage())
                        .build());
                sink.tryEmitComplete();
            }
        });
        return sink.asFlux();
    }
}
