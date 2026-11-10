package com.example.jarvis.controller;

import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 客户端解析验证接口（临时，用于验证 {@link LlmClientFactory} 的配置绑定与解析逻辑）。
 *
 * <p>GET /jarvis/llm/resolve?modelId=mxbc-qwen</p>
 *
 * <p>预期：传具体模型 ID 解析对应平台；不传走默认模型；传不存在的 ID 报 400。</p>
 */
@RestController
@RequestMapping("/jarvis/llm")
@RequiredArgsConstructor
public class LlmResolveController {

    private final LlmClientFactory llmClientFactory;

    /**
     * 解析模型 ID 对应的客户端信息（验证用，不发起真实模型调用）。
     *
     * @param modelId 模型 ID，可空（空则用默认模型）
     * @return 解析结果：模型 ID、平台模型名、参数、baseUrl
     */
    @GetMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam(required = false) String modelId) {
        LlmClientFactory.ResolvedLlmClient client = llmClientFactory.getClient(modelId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelId", client.modelId());
        result.put("modelName", client.modelName());
        result.put("temperature", client.temperature());
        result.put("maxTokens", client.maxTokens());
        result.put("baseUrl", client.baseUrl());
        return result;
    }
}
