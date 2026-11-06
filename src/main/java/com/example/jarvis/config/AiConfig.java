package com.example.jarvis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * AI 配置：构建 Jarvis 使用的 {@link ChatClient} 与网关 {@link WebClient}。
 *
 * <p>OpenAI 兼容模型客户端（{@code OpenAiChatModel}）由 Spring AI 根据
 * {@code application.yaml} 中的 {@code spring.ai.openai.*} 配置自动装配，
 * 这里基于它构建一个带默认系统提示词的 {@link ChatClient}，
 * 作为 Jarvis 非流式对话的统一入口。</p>
 *
 * <p>另外提供一个直连网关的 {@link WebClient}，用于流式对话：
 * 网关的 SSE chunk 存在不带 {@code id} 等字段的精简格式，
 * Spring AI 2.0 底层的 OpenAI SDK 会对其严格校验而中断流，
 * 因此流式链路改为直接消费网关 SSE 并自行解析。</p>
 */
@Configuration
public class AiConfig {

    /**
     * Jarvis 的系统人设，所有对话都会自动带上这段默认提示词。
     */
    public static final String SYSTEM_PROMPT = """
            你是 Jarvis，一个聪明、可靠的 AI 助手。
            请用中文回答，语气专业而友好，回答尽量简洁、清晰、有条理。""";

    @Value("${spring.ai.openai.base-url}")
    private String gatewayBaseUrl;

    @Value("${spring.ai.openai.api-key:}")
    private String gatewayApiKey;

    /**
     * 构建带默认系统提示词的 {@link ChatClient}（用于非流式对话）。
     *
     * @param chatModel Spring AI 自动装配的 OpenAI 兼容模型客户端
     * @return 配置好默认人设的 {@link ChatClient}
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    /**
     * 构建直连公司 AI 网关的 {@link WebClient}（用于流式对话）。
     *
     * @return 已配置网关地址与鉴权头的 {@link WebClient}
     */
    @Bean
    public WebClient gatewayWebClient() {
        return WebClient.builder()
                .baseUrl(gatewayBaseUrl)
                .defaultHeader("Authorization", "Bearer " + gatewayApiKey)
                .build();
    }
}
