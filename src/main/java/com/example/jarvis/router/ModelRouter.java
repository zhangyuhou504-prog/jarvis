package com.example.jarvis.router;

import com.example.jarvis.config.LlmProperties;
import com.example.jarvis.dto.ChatRequest;
import com.example.jarvis.model.IntentResult;
import com.example.jarvis.model.IntentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型路由器：根据请求特点决定使用哪个模型（返回模型 ID，交由 {@code LlmClientFactory} 获取客户端）。
 *
 * <p><b>路由规则（确定性，优先级从高到低）：</b></p>
 * <ol>
 *   <li>用户显式指定 {@code modelId} → 直接使用（用户明确指定优先于一切自动路由）</li>
 *   <li>指定了模板且模板配置了映射模型（{@code jarvis.llm.template-models}）→ 使用模板对应模型</li>
 *   <li>意图识别结果配置了映射模型（{@code jarvis.llm.intent-models}）→ 使用意图对应模型
 *       （{@link IntentType#UNKNOWN} 或映射缺失时跳过，回落默认模型）</li>
 *   <li>以上均不满足 → 使用默认模型（{@code jarvis.llm.default-model}）</li>
 * </ol>
 *
 * <p>职责边界：只回答"用哪个模型 ID"，不负责模型客户端的创建（那是
 * {@code LlmClientFactory} 的职责），也不在 {@code ChatService} 里散落路由判断。</p>
 *
 * <p>后续演进：模型评分/降级届时再引入。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelRouter {

    private final LlmProperties llmProperties;

    /**
     * 根据请求与意图识别结果决定使用哪个模型。
     *
     * @param request 对话请求
     * @param intent  意图识别结果（可为 null：未启用意图识别或识别未执行时）
     * @return 模型 ID（保证非空；调用方交给 LlmClientFactory 校验其存在性）
     */
    public String route(ChatRequest request, IntentResult intent) {
        // 1. 用户显式指定模型优先（用户明确指定 > 系统自动路由）
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

        // 3. 意图 → 模型映射（第五阶段：意图识别结果参与路由）。
        //    UNKNOWN（含识别失败回落）不参与路由，避免把失败流量导向特定模型；
        //    映射表未配置该意图时同样跳过，回落默认模型。
        if (intent != null && intent.intent() != null && intent.intent() != IntentType.UNKNOWN) {
            String modelId = llmProperties.intentModels() == null
                    ? null
                    : llmProperties.intentModels().get(intent.intent().name());
            if (modelId != null && !modelId.isBlank()) {
                log.info("模型路由: 意图[{}] (confidence={}) 映射到模型[{}]",
                        intent.intent(), intent.confidence(), modelId);
                return modelId;
            }
        }

        // 4. 默认模型
        return llmProperties.defaultModel();
    }
}
