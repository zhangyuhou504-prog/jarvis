package com.example.jarvis.service;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import com.example.jarvis.infrastructure.llm.LlmClientService;
import com.example.jarvis.tool.ToolExecutor;
import com.example.jarvis.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;

/**
 * 工具调用闭环：请求模型 → 若返回 tool_calls 则执行并把结果回传 → 再次请求 →
 * 直到模型给出最终文本答案，或达到最大轮数上限。
 *
 * <p><b>职责边界</b>：本服务只负责"把模型的工具调用请求执行完毕"，
 * 不判断用户想做什么（那是 {@link IntentService}），也不选择模型（那是 {@code ModelRouter}）。</p>
 *
 * <p><b>循环安全</b>：最多执行 {@code jarvis.tool.max-rounds} 轮工具调用（默认 5），
 * 超限仍收到 tool_calls 时抛异常终止，防止模型陷入无限调用。</p>
 *
 * <p><b>工具失败不中断循环</b>：单个工具执行失败（未知工具名、参数非法等）时，
 * 把失败原因作为 role=tool 结果回传给模型，由模型自行决定如何回答，
 * 而不是直接把异常抛给客户端。</p>
 *
 * <p><b>当前为非流式实现</b>：每轮等待完整响应（tool_calls 完整返回，解析最简单可靠）。
 * 流式工具调用（按 index 聚合分片 arguments）为第二阶段目标。</p>
 */
@Service
@Slf4j
public class ToolLoopService {

    /**
     * 共享 JSON 解析器（线程安全）。
     */
    private static final JsonMapper JSON = new JsonMapper();

    private final LlmClientService llmClientService;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;

    /**
     * 单次对话最多执行的工具调用轮数。
     */
    @Value("${jarvis.tool.max-rounds:5}")
    private int maxRounds;

    /**
     * 工具循环内单次非流式模型调用超时（秒）。
     */
    @Value("${jarvis.tool.call-timeout-seconds:120}")
    private long callTimeoutSeconds;

    public ToolLoopService(LlmClientService llmClientService, ToolExecutor toolExecutor,
                           ToolRegistry toolRegistry) {
        this.llmClientService = llmClientService;
        this.toolExecutor = toolExecutor;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行工具调用闭环，返回模型最终文本答案。
     *
     * <p>工具定义（{@code tools} 字段）来自 {@link ToolRegistry}，与 {@link ToolExecutor}
     * 的执行白名单是同一份注册表——模型只能调用注册过的工具。</p>
     *
     * @param client   已解析的模型客户端
     * @param messages 初始消息数组（system + 历史 + 当前 user），方法内部会拷贝，不修改入参
     * @return 模型最终文本答案
     * @throws IllegalStateException 工具调用轮数达到上限时模型仍在请求工具
     */
    public String run(LlmClientFactory.ResolvedLlmClient client, ArrayNode messages) {
        ArrayNode working = (ArrayNode) messages.deepCopy();
        int toolRounds = 0;
        while (true) {
            ObjectNode request = buildRequest(client, working);
            JsonNode response = llmClientService.chat(client, request)
                    .block(Duration.ofSeconds(callTimeoutSeconds));

            JsonNode message = response.path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");

            // 无 tool_calls → 最终答案
            if (!toolCalls.isArray() || toolCalls.size() == 0) {
                String content = message.path("content").asText("");
                log.info("工具循环结束: 共执行 {} 轮工具调用, 最终答案长度={}", toolRounds, content.length());
                return content;
            }

            // 达到轮数上限仍请求工具 → 终止，防止无限循环
            if (toolRounds >= maxRounds) {
                throw new IllegalStateException("工具调用轮数达到上限(" + maxRounds + ")，模型仍在请求工具，终止循环");
            }

            // 1. 把带 tool_calls 的 assistant 消息追加进上下文（协议要求原样回传）
            ObjectNode assistantMsg = (ObjectNode) message.deepCopy();
            if (assistantMsg.get("content") == null || assistantMsg.get("content").isNull()) {
                assistantMsg.put("content", "");
            }
            working.add(assistantMsg);

            // 2. 逐个执行工具调用，结果以 role=tool 消息回传
            for (JsonNode toolCall : toolCalls) {
                String callId = toolCall.path("id").asText("");
                String name = toolCall.path("function").path("name").asText("");
                String arguments = toolCall.path("function").path("arguments").asText("{}");
                String result;
                try {
                    result = toolExecutor.execute(name, arguments);
                    log.info("工具调用[第{}轮]: name={}, args={}, result={}", toolRounds + 1, name, arguments, result);
                } catch (Exception e) {
                    // 工具失败不中断循环：把失败原因回传给模型，由模型决定如何回答
                    result = "工具执行失败: " + e.getMessage();
                    log.error("工具执行失败: name={}, args={}", name, arguments, e);
                }
                ObjectNode toolMsg = JSON.createObjectNode();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", callId);
                toolMsg.put("content", result);
                working.add(toolMsg);
            }
            toolRounds++;
        }
    }

    /**
     * 构建单轮非流式请求体（OpenAI 兼容格式，含 tools 定义）。
     *
     * @param client   模型客户端
     * @param messages 当前上下文消息
     * @return 请求体
     */
    private ObjectNode buildRequest(LlmClientFactory.ResolvedLlmClient client, ArrayNode messages) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", client.modelName());
        body.put("stream", false);
        if (client.temperature() != null) {
            body.put("temperature", client.temperature());
        }
        if (client.maxTokens() != null) {
            body.put("max_tokens", client.maxTokens());
        }
        if (client.enableThinking() != null) {
            body.put("enable_thinking", client.enableThinking());
        }
        body.set("messages", messages);
        // 工具定义统一来自注册表（与执行白名单同源）
        body.set("tools", toolRegistry.definitions());
        return body;
    }
}
