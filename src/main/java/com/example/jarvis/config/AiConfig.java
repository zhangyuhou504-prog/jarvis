package com.example.jarvis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 配置：系统提示词 + Spring AI {@link ChatClient} + 模型调用层 {@link WebClient}。
 *
 * <p>三个职责：</p>
 * <ul>
 *   <li>系统提示词：从 {@code classpath:prompts/system-prompt.md} 启动时一次性读入（fail-fast）</li>
 *   <li>{@link ChatClient}：基于 Spring AI 自动装配的 {@code ChatModel}（非流式链路）</li>
 *   <li>{@link WebClient}：按 {@link LlmProperties} 中默认模型对应的连接构建（流式链路）</li>
 * </ul>
 *
 * <p>流式走 WebClient 直连的原因：网关 SSE chunk 存在不带 {@code id} 等字段的精简格式，
 * Spring AI 2.0 底层 OpenAI SDK 严格校验会中断流（已对照实验验证）。</p>
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class AiConfig {

    /**
     * 系统提示词（启动时从 Markdown 文件一次性读入并缓存）。
     */
    private final String systemPrompt;

    public AiConfig(@Value("classpath:prompts/system-prompt.md") Resource systemPromptResource)
            throws IOException {
        this.systemPrompt = new String(systemPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * 获取系统提示词，供非流式（{@link ChatClient}）与流式（{@code ChatService}）两条链路共用。
     *
     * @return 系统提示词文本
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * 构建带默认系统提示词的 {@link ChatClient}（用于非流式对话）。
     *
     * @param chatModel Spring AI 自动装配的 OpenAI 兼容模型客户端
     * @return 配置好默认人设的 {@link ChatClient}
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .build();
    }

    /**
     * 构建模型调用层 {@link WebClient}：按 {@link LlmProperties} 中默认模型解析连接。
     *
     * <p>配置错误（默认模型/连接不存在）在启动时 fail-fast。</p>
     *
     * @param properties 统一模型配置
     * @return 已配置连接地址与鉴权头的 {@link WebClient}
     */
    @Bean
    public WebClient gatewayWebClient(LlmProperties properties) {
        LlmProperties.Model model = properties.models().get(properties.defaultModel());
        if (model == null) {
            throw new IllegalStateException("默认模型不存在: " + properties.defaultModel());
        }
        LlmProperties.Connection connection = properties.connections().get(model.connection());
        if (connection == null) {
            throw new IllegalStateException("模型连接不存在: " + model.connection());
        }

        WebClient.Builder builder = WebClient.builder()
                .baseUrl(connection.baseUrl());
        if (connection.apiKey() != null && !connection.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + connection.apiKey());
        }
        return builder.build();
    }
}
