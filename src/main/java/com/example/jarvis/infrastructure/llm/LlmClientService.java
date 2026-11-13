package com.example.jarvis.infrastructure.llm;

import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 统一模型调用服务：负责向模型平台发送 HTTP 请求并解析响应。
 *
 * <p><b>职责边界</b>：</p>
 * <ul>
 *   <li>{@link LlmClientFactory}：模型 ID → 客户端（连接信息 + WebClient）的解析</li>
 *   <li>本服务：客户端 + 请求体 → 响应 JSON 的发送与解析</li>
 *   <li>{@code ToolLoopService}：工具调用循环的控制（不在本服务里掺和）</li>
 * </ul>
 *
 * <p><b>请求体为什么用 {@link ObjectNode} 而非强类型 DTO</b>：OpenAI 兼容协议的
 * 请求字段会随能力增加（tools、tool_choice、平台扩展参数），ObjectNode 保持灵活，
 * 避免 DTO 随平台差异不断膨胀。</p>
 *
 * <p><b>当前只提供非流式调用</b>：工具调用闭环第一阶段用非流式（tool_calls 完整返回，
 * 解析最简单可靠）。流式 SSE 链路仍由 ChatService 直接走 WebClient（已验收，不动），
 * 待非流式工具闭环验证通过后再统一升级。</p>
 *
 * <p><b>为什么用 tools.jackson（Jackson 3）</b>：Spring Boot 4 的 Web 层序列化走
 * Jackson 3，与 {@code ChatService}/{@code IntentService} 的解析器保持一致。</p>
 */
public interface LlmClientService {

    /**
     * 非流式调用模型，返回完整响应 JSON。
     *
     * @param client  已解析的模型客户端（含 WebClient 与模型参数）
     * @param request 请求体（OpenAI 兼容格式，{@code stream} 应为 {@code false}）
     * @return 响应 JSON（含 {@code choices} 等字段）；HTTP 错误由 WebClient 以异常形式抛出
     */
    Mono<JsonNode> chat(LlmClientFactory.ResolvedLlmClient client, ObjectNode request);
}
