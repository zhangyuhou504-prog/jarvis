package com.example.jarvis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 配置：构建 Jarvis 使用的 {@link ChatClient}。
 *
 * <p>OpenAI 兼容模型客户端（{@code OpenAiChatModel}）由 Spring AI 根据
 * {@code application.yaml} 中的 {@code spring.ai.openai.*} 配置自动装配，
 * 这里只需基于它构建一个带默认系统提示词的 {@link ChatClient}，
 * 作为 Jarvis 对话能力的统一入口。</p>
 */
@Configuration
public class AiConfig {

    /**
     * Jarvis 的系统人设，所有对话都会自动带上这段默认提示词。
     */
    private static final String SYSTEM_PROMPT = """
            你是 Jarvis，一个聪明、可靠的 AI 助手。
            请用中文回答，语气专业而友好，回答尽量简洁、清晰、有条理。""";

    /**
     * 构建带默认系统提示词的 {@link ChatClient}。
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
}
