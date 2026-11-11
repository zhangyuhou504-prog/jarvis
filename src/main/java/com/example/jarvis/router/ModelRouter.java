package com.example.jarvis.router;

import com.example.jarvis.config.LlmProperties;
import com.example.jarvis.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型路由器：根据请求特点决定使用哪个模型（返回模型 ID，交由 {@code LlmClientFactory} 获取客户端）。
 *
 * <p><b>第一版路由规则（确定性，优先级从高到低）：</b></p>
 * <ol>
 *   <li>用户显式指定 {@code modelId} → 直接使用</li>
 *   <li>指定了模板且模板配置了映射模型（{@code jarvis.llm.template-models}）→ 使用模板对应模型</li>
 *   <li>以上均不满足 → 使用默认模型（{@code jarvis.llm.default-model}）</li>
 * </ol>
 *
 * <p>职责边界：只回答"用哪个模型 ID"，不负责模型客户端的创建（那是
 * {@code LlmClientFactory} 的职责），也不在 {@code ChatService} 里散落路由判断。</p>
 *
 * <p>后续演进：第五阶段意图识别后，可在规则 2 和规则 3 之间插入
 * "按识别出的意图选模型"；模型评分/降级届时再引入。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelRouter {

    private final LlmProperties llmProperties;

    /**
     * 根据请求决定使用哪个模型。
     *
     * @param request 对话请求
     * @return 模型 ID（保证非空；调用方交给 LlmClientFactory 校验其存在性）
     */
    public String route(ChatRequest request) {
        // 1. 用户显式指定模型优先
        if (request.getModelId() != null && !request.getModelId().isBlank()) {
            return request.getModelId().trim();
        }

        // 2. 模板 → 模型映射（第一版确定性规则）
        String template = request.getTemplate();
        if (template != null && !template.isBlank()) {
            String modelId = llmProperties.templateModels().get(template);
            if (modelId != null && !modelId.isBlank()) {
                log.info("模型路由: 模板[{}]映射到模型[{}]", template, modelId);
                return modelId;
            }
        }

        // 3. 默认模型
        return llmProperties.defaultModel();
    }
}
