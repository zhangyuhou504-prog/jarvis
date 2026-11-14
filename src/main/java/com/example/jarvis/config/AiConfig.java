package com.example.jarvis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 配置：系统提示词 + Spring AI {@link ChatClient}。
 *
 * <p>两个职责：</p>
 * <ul>
 *   <li>系统提示词：从 {@code classpath:prompts/system-prompt.md} 启动时一次性读入（fail-fast）</li>
 *   <li>{@link ChatClient}：基于 Spring AI 自动装配的 {@code ChatModel}（非流式链路）</li>
 * </ul>
 *
 * <p>流式链路的模型客户端由 {@code LlmClientFactory} 按 {@link LlmProperties}
 * 按模型 ID 解析（多平台），不在本类构建。</p>
 */
@Configuration
@EnableConfigurationProperties({LlmProperties.class, StrategyProperties.class})
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
}
