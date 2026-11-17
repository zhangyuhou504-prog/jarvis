package com.example.jarvis.service;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 最终答案生成服务（8.6.3 收尾）：把内部执行报告转换为面向用户的自然语言回答。
 *
 * <p><b>职责定位</b>：PLANNED 链路执行完成后，内部报告是诊断视角的结构化文本
 * （stepId / SUCCESS / FAILED 等术语），不适合直接给用户。本服务把
 * 「用户原始问题 + 执行报告」交给模型做一次收尾生成——让 Jarvis 从
 * "执行完任务后返回日志"升级为"执行完任务后理解结果并回答用户"。</p>
 *
 * <p><b>职责边界</b>：只做"报告 → 用户回答"的文本转换。不重新执行任何任务、
 * 不调用工具、不修改执行结果——执行记录里没有的信息它不编造
 * （Prompt 已约束，失败如实转述）。</p>
 *
 * <p><b>模型选择</b>：调用方传入模型 ID，经 {@link LlmClientFactory} 解析——
 * 不硬编码模型名称；{@code modelId} 为 null/空白时工厂回落默认模型
 * （与 Planner / StrategyClassifier 同一套体系）。</p>
 *
 * <p><b>复用基础设施</b>：模型调用走 {@link LlmClientService}（统一非流式层），
 * 不新建 WebClient；Prompt 为 classpath:prompts/final-answer-prompt.md。</p>
 */
@Service
@Slf4j
public class FinalAnswerService {

    /**
     * 最终答案生成的模型调用超时（秒）。输入是一段报告文本、输出是一段自然语言，
     * 与策略分类同量级。
     */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(90);

    private final LlmClientService llmClientService;
    private final LlmClientFactory llmClientFactory;
    private final ObjectMapper objectMapper;

    /**
     * 最终答案 Prompt（classpath:prompts/final-answer-prompt.md），构造时加载。
     */
    private String finalAnswerPrompt;

    public FinalAnswerService(
            LlmClientService llmClientService,
            LlmClientFactory llmClientFactory,
            ObjectMapper objectMapper,
            @Value("classpath:prompts/final-answer-prompt.md")
            Resource finalAnswerPromptResource) {
        this.llmClientService = llmClientService;
        this.llmClientFactory = llmClientFactory;
        this.objectMapper = objectMapper;
        try (InputStream inputStream = finalAnswerPromptResource.getInputStream()) {
            this.finalAnswerPrompt = new String(
                    inputStream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException e) {
            throw new IllegalStateException("最终答案 Prompt 加载失败", e);
        }
        log.info("Final Answer Prompt 加载完成, 长度={}", finalAnswerPrompt.length());
    }

    /**
     * 基于执行报告生成面向用户的最终回答。
     *
     * <p>输入组装：system（最终答案 Prompt）+ user（用户原始需求 + 任务执行记录，
     * 按 Prompt 约定的两段式结构）。user 消息 content 必须是字符串——
     * OpenAI 兼容协议不接受 JSON 对象（策略分类联调时踩过的坑）。</p>
     *
     * @param userMessage     用户原始问题（非空）
     * @param executionReport 内部执行报告（PlanExecutionReportBuilder 产物，非空）
     * @param modelId         最终答案使用的模型 ID；null/空白回落默认模型
     * @return 面向用户的自然语言回答
     * @throws IllegalStateException 模型无返回或内容为空（调用方决定兜底策略）
     */
    public String generate(String userMessage, String executionReport, String modelId) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage 不能为空");
        }
        if (executionReport == null || executionReport.isBlank()) {
            throw new IllegalArgumentException("executionReport 不能为空");
        }

        LlmClientFactory.ResolvedLlmClient client =
                llmClientFactory.getClient(modelId);

        ObjectNode request = buildRequest(client, userMessage, executionReport);
        JsonNode response = llmClientService.chat(client, request)
                .block(CALL_TIMEOUT);

        String content = response.path("choices").path(0)
                .path("message").path("content")
                .asText(null);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("FinalAnswer 模型没有返回有效内容");
        }
        log.info("最终答案生成完成, 长度={}", content.length());
        return content;
    }

    /**
     * 构建非流式请求体：system + user 两条消息，无 tools。
     */
    private ObjectNode buildRequest(
            LlmClientFactory.ResolvedLlmClient client,
            String userMessage,
            String executionReport) {
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
        systemMessage.put("content", finalAnswerPrompt);

        ObjectNode userMessageNode = messages.addObject();
        userMessageNode.put("role", "user");
        userMessageNode.put("content", buildUserContent(userMessage, executionReport));

        return request;
    }

    /**
     * 组装 user 消息文本：用户原始需求 + 任务执行记录（Prompt 约定的两段式）。
     */
    private String buildUserContent(String userMessage, String executionReport) {
        return "用户原始需求：\n" + userMessage
                + "\n\n任务执行记录：\n" + executionReport;
    }
}
