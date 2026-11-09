package com.example.jarvis.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * AI 配置：构建 Jarvis 使用的 {@link ChatClient} 与网关 {@link WebClient}。
 *
 * <p>OpenAI 兼容模型客户端（{@code OpenAiChatModel}）由 Spring AI 根据
 * {@code application.yaml} 中的 {@code spring.ai.openai.*} 配置自动装配，
 * 这里基于它构建一个带默认系统提示词的 {@link ChatClient}，
 * 作为 Jarvis 非流式对话的统一入口。</p>
 *
 * <p>系统提示词从独立文件 {@code classpath:prompts/system-prompt.md} 加载，
 * 与代码解耦，便于维护和版本管理；启动时一次性读入并缓存，文件缺失则启动失败（fail-fast）。</p>
 *
 * <p>另外提供一个直连网关的 {@link WebClient}，用于流式对话：
 * 网关的 SSE chunk 存在不带 {@code id} 等字段的精简格式，
 * Spring AI 2.0 底层的 OpenAI SDK 会对其严格校验而中断流，
 * 因此流式链路改为直接消费网关 SSE 并自行解析。</p>
 */
@Configuration
public class AiConfig {

    /**
     * 系统提示词（启动时从 Markdown 文件一次性读入并缓存）。
     */
    private final String systemPrompt;

    private final String gatewayBaseUrl;
    private final String gatewayApiKey;

    public AiConfig(@Value("classpath:prompts/system-prompt.md") Resource systemPromptResource,
                    @Value("${spring.ai.openai.base-url}") String gatewayBaseUrl,
                    @Value("${spring.ai.openai.api-key:}") String gatewayApiKey) throws IOException {
        this.systemPrompt = new String(systemPromptResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.gatewayApiKey = gatewayApiKey;
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
