package com.example.jarvis.plan;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 任务规划服务：把自然语言目标转换成 {@link Plan} 对象。
 *
 * <p><b>职责边界（第七阶段 7.2.4）</b>：只做三件事——
 * 读取规划 Prompt、调用模型生成计划、把模型返回的 JSON 解析为 Plan。
 * 不负责执行步骤，也不负责调用工具（那是 PlanExecutor / Tool Calling 的职责）。</p>
 *
 * <p>模型调用复用 {@link LlmClientService}（统一非流式调用层），
 * 使用默认模型客户端（{@code jarvis.llm.default-model}）；
 * 请求结构为 system（planner-prompt.md）+ user（用户目标）两条消息，
 * 无历史、无 tools。</p>
 *
 * <p><b>健壮性</b>：模型不保证 100% 遵守"只输出 JSON"的指令，
 * 解析前会剥掉可能的 markdown 代码块围栏；JSON 不合法时抛
 * {@link IllegalStateException}。</p>
 *
 * <p><b>合法性校验（第七阶段 7.4）</b>：解析出 Plan 后交给 {@link PlanValidator}
 * 做语义级校验（stepId 唯一 → 依赖引用存在 → 无环）。</p>
 *
 * <p><b>重试机制</b>：模型输出是概率性的（实测出现过偶发空 steps、漏 goal 字段、
 * 非法 JSON），失败时把具体错误原因回传给模型重试（总次数
 * {@code jarvis.planner.max-attempts}，默认 2 = 首次 + 1 次重试）；
 * 超限后抛出最后一次异常（保留原类型与消息，由上层决定如何向用户报错）。</p>
 *
 * <p><b>为什么用 tools.jackson（Jackson 3）</b>：Spring Boot 4 自动装配的
 * {@link ObjectMapper} 是 Jackson 3 的，与项目其他组件保持一致。</p>
 */
@Service
@Slf4j
public class PlannerService {

    /**
     * Planner 模型调用超时（秒）。规划输出比意图分类长，给足时间。
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 计划生成总尝试次数（首次 + 重试）。
     * 失败时把错误原因回传给模型重试，超限后抛出最后一次异常。
     */
    @Value("${jarvis.planner.max-attempts:2}")
    private int maxAttempts;

    private final LlmClientService llmClientService;
    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;
    private final PlanValidator planValidator;
    private final Resource plannerPromptResource;

    private String plannerPrompt;

    public PlannerService(
            LlmClientService llmClientService,
            LlmClientFactory llmClientFactory,
            ObjectMapper objectMapper,
            PlanValidator planValidator,
            @Value("classpath:prompts/planner-prompt.md")
            Resource plannerPromptResource) {
        this.llmClientService = llmClientService;
        this.llmClientFactory = llmClientFactory;
        this.objectMapper = objectMapper;
        this.planValidator = planValidator;
        this.plannerPromptResource = plannerPromptResource;
    }

    /**
     * 启动时一次性读入规划 Prompt（classpath:prompts/planner-prompt.md）。
     */
    @PostConstruct
    public void init() throws IOException {
        try (InputStream inputStream = plannerPromptResource.getInputStream()) {
            this.plannerPrompt = new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }
        log.info("Planner Prompt 加载完成, 长度={}", plannerPrompt.length());
    }

    /**
     * 根据用户目标生成任务计划（带重试）。
     *
     * <p>每次尝试：调模型 → 解析 Plan → 校验。任一环节失败（模型无返回、
     * 内容为空、JSON 不合法、计划语义不合法）时，把具体错误原因拼进 user 消息
     * 让模型修正后重试；总尝试次数为 {@code maxAttempts}（默认 2 = 首次 + 1 次重试）。
     * 超限后抛出<b>最后一次</b>异常（保留原类型与消息，由上层决定如何向用户报错）。</p>
     *
     * @param goal 用户希望完成的最终目标（自然语言）
     * @return 任务计划（goal + 步骤列表），已通过 PlanValidator 合法性校验
     * @throws IllegalStateException    重试耗尽，最后一次失败为模型无返回/内容为空/JSON 不合法
     * @throws IllegalArgumentException 重试耗尽，最后一次失败为计划语义不合法
     */
    public Plan createPlan(String goal) {

        // 1. 获取默认模型客户端（重试间复用，WebClient 按连接缓存）
        LlmClientFactory.ResolvedLlmClient client =
                llmClientFactory.getClient(null);

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // 首次发原始目标；重试时附带上次失败原因，让模型定向修正
            String userContent = attempt == 1
                    ? goal
                    : goal + "\n\n[修正指令] 你上次生成的计划不合法：" + lastError.getMessage()
                            + "。请修正后重新输出完整计划 JSON。";
            try {
                String content = callModel(client, userContent);
                Plan plan = parsePlan(content, goal);
                // 语义级合法性校验（唯一性 → 引用存在 → 无环），失败抛 IllegalArgumentException
                planValidator.validate(plan);
                if (attempt > 1) {
                    log.info("计划重试后生成成功, 第{}次尝试", attempt);
                }
                log.info("计划生成完成: goal={}, 步骤数={}", plan.goal(), plan.steps().size());
                return plan;
            } catch (RuntimeException e) {
                lastError = e;
                log.warn("计划生成失败(第{}/{}次尝试): {}", attempt, maxAttempts, e.getMessage());
            }
        }

        // 重试耗尽：抛出最后一次异常（保留原类型与消息）
        throw lastError;
    }

    /**
     * 调用模型并提取 content。
     *
     * @param client      模型客户端
     * @param userContent 用户消息（首次为目标原文，重试时附带修正指令）
     * @return 模型返回的 content 文本
     * @throws IllegalStateException 模型无返回或内容为空
     */
    private String callModel(LlmClientFactory.ResolvedLlmClient client, String userContent) {
        // 构建请求：system（规划 Prompt）+ user（用户目标 / 目标+修正指令）
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", client.modelName());
        request.put("stream", false);

        if (client.temperature() != null) {
            request.put("temperature", client.temperature());
        }

        if (client.maxTokens() != null) {
            request.put("max_tokens", client.maxTokens());
        }

        if (client.enableThinking() != null) {
            request.put("enable_thinking", client.enableThinking());
        }

        ArrayNode messages = request.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", plannerPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userContent);

        // 非流式调用，block 等待完整响应
        JsonNode response = llmClientService
                .chat(client, request)
                .block(CALL_TIMEOUT);

        if (response == null) {
            throw new IllegalStateException("Planner 模型调用没有返回结果");
        }

        String content = response
                .path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText(null);

        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Planner 模型没有返回有效内容");
        }
        return content;
    }

    /**
     * 把模型输出解析为 Plan（剥代码块围栏 + goal 缺失回落）。
     *
     * @param content 模型原始输出
     * @param goal    用户原始目标（goal 字段缺失时的回落值）
     * @return Plan 对象（未做语义校验）
     * @throws IllegalStateException JSON 不合法
     */
    private Plan parsePlan(String content, String goal) {
        String json = stripCodeFence(content);
        try {
            JsonNode root = objectMapper.readTree(json);
            // 容错归一：模型偶尔省略 goal 或输出空 goal（实测简单目标时出现过），
            // 回落为用户原始目标——计划的 goal 本就是用户的目标本身
            if (root.isObject()) {
                JsonNode goalNode = root.path("goal");
                if (goalNode.isMissingNode() || goalNode.isNull() || goalNode.asText("").isBlank()) {
                    ((ObjectNode) root).put("goal", goal);
                }
            }
            return objectMapper.treeToValue(root, Plan.class);
        } catch (JacksonException e) {
            log.error("Planner JSON 解析失败，模型返回内容: {}", content, e);
            throw new IllegalStateException("Planner 返回的计划格式不正确", e);
        }
    }

    /**
     * 剥掉模型可能违规输出的 markdown 代码块围栏（```json ... ```）。
     * Prompt 已要求不要输出代码块，但模型不保证 100% 遵守，解析侧兜底。
     *
     * @param content 模型原始输出
     * @return 剥离围栏后的 JSON 文本
     */
    private String stripCodeFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
