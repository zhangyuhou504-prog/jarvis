package com.example.jarvis.service;

import com.example.jarvis.domain.ChatMessage;
import com.example.jarvis.domain.Conversation;
import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.dto.ChatResponse;
import com.example.jarvis.exception.AiServiceException;
import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 对话业务：调用大模型生成回答。
 *
 * <p>非流式走 Spring AI {@link ChatClient}（暂未接入多平台）；
 * 流式走 {@link LlmClientFactory} 按模型解析出的 WebClient 直连平台，
 * 自行解析 SSE（各平台 chunk 格式可能有差异，当前只提取
 * {@code choices[0].delta.content}，不做过度适配）。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final LlmClientFactory llmClientFactory;
    private final PromptTemplateService promptTemplateService;
    private final ConversationService conversationService;

    /**
     * 用于解析 SSE chunk 的共享 JSON 解析器（线程安全）。
     */
    private static final JsonMapper JSON = new JsonMapper();

    /**
     * 流式：相邻两个原始 SSE 事件（平台 chunk）之间的最大等待时间（秒）。
     * 覆盖"连接挂起"与"生成中断"两种长时间无响应场景。
     */
    @Value("${jarvis.chat.stream-element-timeout-seconds:120}")
    private long streamElementTimeoutSeconds;

    /**
     * 是否启用模型思考（reasoning）阶段。
     *
     * <p><b>注意：这是公司网关（mxbc）的特定扩展参数，不是所有 OpenAI 兼容平台都识别，
     * 因此暂时不从全局请求体下发。</b>背景：公司网关的推理模型思考阶段有时整段缓冲、
     * 长时间不推 chunk，{@code enable_thinking:false} 可规避。
     * 下一步迁移为 {@code LlmProperties.Model} 的每模型可选配置，由具体模型决定是否传递。</p>
     */
    @Value("${jarvis.chat.enable-thinking:false}")
    private boolean enableThinking;

    /**
     * 多轮对话携带的历史消息条数上限（user/assistant 各算一条）。
     * 防止长会话把 token 打爆；超出时只取最近 N 条。
     */
    @Value("${jarvis.chat.max-history-messages:20}")
    private int maxHistoryMessages;

    /**
     * 处理一轮对话，返回模型回答（非流式）。
     *
     * <p>注意：本方法仍走 Spring AI 自动装配的 {@link ChatClient}（公司网关），
     * 尚未接入多平台；等流式多平台验证通过后再决定是否统一。</p>
     *
     * @param request 对话请求
     * @return 包含模型回答的响应
     */
    public ChatResponse chat(ChatRequest request) {
        log.info("收到对话请求: {}", request.getMessage());
        String answer;
        try {
            answer = chatClient.prompt()
                    .user(request.getMessage())
                    .call()
                    .content();
        } catch (Exception e) {
            // 完整堆栈只进日志；对客户端只暴露友好提示（由 GlobalExceptionHandler 转 502）
            log.error("模型调用失败: {}", e.getMessage(), e);
            throw new AiServiceException("模型服务暂时不可用，请稍后重试", e);
        }
        log.info("模型回答完成, 长度={}", answer == null ? 0 : answer.length());
        return new ChatResponse(answer);
    }

    /**
     * 流式对话：以响应式流逐段返回模型生成的文本。
     *
     * <p>通过 {@link LlmClientFactory} 解析本次使用的模型客户端
     * （当前先使用默认模型，后续由 ModelRouter 自动决定），
     * 逐条解析 SSE 数据中的 {@code choices[0].delta.content}。</p>
     *
     * <p>生命周期：{@code doOnNext} 旁路收集完整答案；{@code timeout} 控制元素间超时；
     * {@code doOnError} 识别流中异常；{@code onErrorResume} 在响应已提交后优雅收尾；
     * {@code doFinally} 在 onComplete/onError/cancel（客户端断连）时统一收尾并释放。</p>
     *
     * @param request 对话请求
     * @return 文本分片流，每个元素为模型生成的一小段文本
     */
    public Flux<String> chatStream(ChatRequest request) {
        log.info("收到流式对话请求: conversationId={}, message={}",
                request.getConversationId(), request.getMessage());
        // 旁路收集完整答案。Reactor 对同一订阅的 onNext 串行回调，StringBuilder 无需加锁。
        StringBuilder fullAnswer = new StringBuilder();
        // 标记是否发生过异常，流成功完成后才把本轮问答写入会话（失败/断连不留悬空消息）。
        AtomicBoolean errored = new AtomicBoolean(false);

        // 多轮对话：加载会话历史（conversationId 为空则单轮对话，无历史）。
        // 会话不存在时 getConversation 抛 NotFoundException，在流构建前抛出，正常返回 404。
        Conversation conversation = (request.getConversationId() == null || request.getConversationId().isBlank())
                ? null
                : conversationService.getConversation(request.getConversationId());
        List<ChatMessage> history = conversation == null ? List.of() : snapshotHistory(conversation);

        // 当前先使用默认模型，后续由 ModelRouter 自动决定
        LlmClientFactory.ResolvedLlmClient client = llmClientFactory.getClient(null);
        log.info("本次流式使用模型: modelId={}, modelName={}, baseUrl={}",
                client.modelId(), client.modelName(), client.baseUrl());

        Map<String, Object> body = streamBody(history, request.getMessage(), resolveSystemPrompt(request), client);

        // 打印实际请求参数（DEBUG 级，避免生产环境泄露对话内容；序列化失败不影响主流程）
        try {
            log.debug("模型请求参数: {}", JSON.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            log.warn("请求参数序列化失败: {}", e.getMessage());
        }

        return client.webClient().post()
                .uri("/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                // 元素间超时——放在原始 SSE 事件级：平台只要还在发任何 chunk（含推理模型思考阶段的
                // reasoning chunk、空内容 chunk）就不算超时；只有平台真正静默超过该时长（连接挂起、生成中断）才中断。
                // 注意：不能放在 filter 空内容之后，否则推理模型思考期（content 全为空）会被误判为无响应。
                .timeout(Duration.ofSeconds(streamElementTimeoutSeconds))
                .filter(Objects::nonNull)
                .map(ServerSentEvent::data)
                .doOnNext(data -> log.info("原始模型chunk: {}", data))
                .map(this::extractContent)
                .filter(content -> !content.isEmpty())
                // 每个 chunk 旁路拼接到完整答案（实时推送与收集并行）
                .doOnNext(fullAnswer::append)
                // 识别流中异常（模型调用失败 / 生成中报错 / 超时）
                .doOnError(err -> {
                    errored.set(true);
                    log.error("流式回答异常, 已收集长度={}: {}", fullAnswer.length(), err.getMessage(), err);
                })
                // 流中错误兜底——响应已提交无法改状态码，转为友好提示并优雅结束
                .onErrorResume(err -> Flux.just("[提示] 回答生成中断，请稍后重试"))
                // 流正常收尾（含被 onErrorResume 恢复后的收尾）
                .doOnComplete(() -> {
                    log.info("流式回答收尾, 完整答案长度={}, 内容={}", fullAnswer.length(), fullAnswer);
                    // 仅在本轮成功完成时写入会话，保证历史里只有完整的问答对
                    if (conversation != null && !errored.get()) {
                        appendTurn(conversation, request.getMessage(), fullAnswer.toString());
                    }
                })
                // 最终释放——onComplete / onError / cancel（客户端断连）都会走到这里。
                // 客户端断连时 Reactor 的 cancel 向上传播、取消上游 WebClient 请求，不再做无谓后续工作。
                .doFinally(signal -> log.info("流式结束, 信号={}, 是否异常={}, 已收集长度={}",
                        signal, errored.get(), fullAnswer.length()));
    }

    /**
     * 读取会话历史快照（最多最近 {@link #maxHistoryMessages} 条）。
     *
     * <p>对会话对象加锁，保证与 {@link #appendTurn} 的追加互斥，
     * 快照本身是独立副本，后续追加不影响已发出的请求。</p>
     *
     * @param conversation 会话
     * @return 历史消息副本（按时间顺序）
     */
    private List<ChatMessage> snapshotHistory(Conversation conversation) {
        synchronized (conversation) {
            List<ChatMessage> all = conversation.getMessages();
            int from = Math.max(0, all.size() - maxHistoryMessages);
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    /**
     * 把本轮问答（user + assistant）追加到会话历史。
     *
     * @param conversation    会话
     * @param userMessage     本轮用户消息
     * @param assistantAnswer 本轮模型完整回答
     */
    private void appendTurn(Conversation conversation, String userMessage, String assistantAnswer) {
        synchronized (conversation) {
            conversation.getMessages().add(new ChatMessage("user", userMessage));
            conversation.getMessages().add(new ChatMessage("assistant", assistantAnswer));
        }
        log.info("会话[{}]追加本轮问答, 当前历史条数={}",
                conversation.getConversationId(), conversation.getMessages().size());
    }

    /**
     * 构建流式请求体（OpenAI 兼容格式）。
     *
     * <p>messages 数组结构：{@code [system] + [历史 user/assistant...] + [当前 user]}。
     * system 提示词每次动态拼接（不存会话），历史消息来自会话快照。</p>
     *
     * <p>{@code temperature}/{@code max_tokens} 为可选参数，模型未配置时不传，
     * 让平台使用默认值。平台扩展参数（如公司网关的 {@code enable_thinking}）
     * 不在此全局下发，待迁移到每模型配置后按模型传递。</p>
     *
     * @param history      会话历史消息（可为空，单轮对话时为空）
     * @param message      当前用户消息
     * @param systemPrompt 本轮系统提示词（模板渲染结果）
     * @param client       本次使用的模型客户端
     * @return 请求体
     */
    private Map<String, Object> streamBody(List<ChatMessage> history, String message, String systemPrompt,
                                           LlmClientFactory.ResolvedLlmClient client) {
        Map<String, Object> body = new LinkedHashMap<>();

        // 使用本次选中的平台模型名称
        body.put("model", client.modelName());
        body.put("stream", true);

        // 可选参数：没有配置时不传，让平台使用默认值
        if (client.temperature() != null) {
            body.put("temperature", client.temperature());
        }
        if (client.maxTokens() != null) {
            body.put("max_tokens", client.maxTokens());
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(messageOf("system", systemPrompt));
        for (ChatMessage m : history) {
            messages.add(messageOf(m.getRole(), m.getContent()));
        }
        messages.add(messageOf("user", message));
        body.put("messages", messages);
        return body;
    }

    /**
     * 构建单条消息。
     *
     * @param role    角色
     * @param content 内容
     * @return 消息 Map
     */
    private Map<String, String> messageOf(String role, String content) {
        Map<String, String> msg = new LinkedHashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    /**
     * 解析本轮对话使用的系统提示词。
     *
     * <p>统一走模板注册表：不传 {@code template} 时用默认模板；传了则按标识渲染
     * （模板标识未注册会抛 {@link IllegalArgumentException}，由全局异常处理转 400）。
     * 模板变量由 Jarvis 内部维护，上游只传标识。</p>
     *
     * @param request 对话请求
     * @return 本轮使用的系统提示词
     */
    private String resolveSystemPrompt(ChatRequest request) {
        String templateName = (request.getTemplate() == null || request.getTemplate().isBlank())
                ? PromptTemplateService.DEFAULT_TEMPLATE
                : request.getTemplate();
        String prompt = promptTemplateService.render(templateName);
        // 打印最终生成的 Prompt（DEBUG 级，避免生产环境泄露对话内容）
        log.debug("模板[{}]渲染后的系统提示词: {}", templateName, prompt);
        return prompt;
    }

    /**
     * 从一条 SSE 数据中提取文本内容。
     *
     * <p>兼容网关两种 chunk 格式：标准 chunk 与精简 chunk
     * （后者不带 {@code id} 等字段，只有 {@code choices[0].delta.content}）。
     * 不同平台的 chunk 格式可能有差异（元数据、reasoning_content、结束标志、[DONE]），
     * 当前只提取正文，不提前做平台适配。</p>
     *
     * @param data SSE 事件数据（JSON 字符串，或 {@code [DONE]}）
     * @return 文本内容；非文本事件返回空串
     */
    private String extractContent(String data) {
        String json = data.trim();
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return "";
        }
        try {
            JsonNode node = JSON.readTree(json);
            JsonNode choices = node.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                return choices.get(0).path("delta").path("content").asText("");
            }
        } catch (Exception e) {
            log.warn("解析 SSE chunk 失败: {}", e.getMessage());
        }
        return "";
    }
}
