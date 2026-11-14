package com.example.jarvis.strategy;

import com.example.jarvis.config.LlmProperties;
import com.example.jarvis.config.StrategyProperties;
import com.example.jarvis.infrastructure.llm.LlmClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * StrategyProperties YAML 绑定测试（8.2.6 第一个任务）。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li>{@code jarvis.strategy.model-id} 正确绑定到 StrategyProperties</li>
 *   <li>配置的 modelId 是 {@code jarvis.llm.models} 中已注册的 key
 *       （防止误配平台原始模型名如 qwen3.8-27b，启动即暴露）</li>
 *   <li>该 modelId 能通过 {@link LlmClientFactory} 解析出真实客户端
 *       （完整链路：YAML → StrategyProperties → LlmProperties → WebClient）</li>
 * </ol>
 */
@SpringBootTest
class StrategyPropertiesTest {

    @Autowired
    private StrategyProperties strategyProperties;

    @Autowired
    private LlmProperties llmProperties;

    @Autowired
    private LlmClientFactory llmClientFactory;

    @Test
    void shouldBindModelIdFromYaml() {
        assertEquals("mxbc-qwen", strategyProperties.modelId());
    }

    @Test
    void modelIdShouldBeRegisteredInLlmModels() {
        // 防御：配置的必须是已注册模型 key，不是平台原始模型名
        String modelId = strategyProperties.modelId();
        assertNotNull(modelId, "jarvis.strategy.model-id 未配置");
        assertNotNull(llmProperties.models().get(modelId),
                "jarvis.strategy.model-id=" + modelId + " 未在 jarvis.llm.models 中注册");
    }

    @Test
    void modelIdShouldResolveToRealClient() {
        // 完整链路验证：modelId → LlmClientFactory → 真实客户端
        LlmClientFactory.ResolvedLlmClient client =
                llmClientFactory.getClient(strategyProperties.modelId());
        assertNotNull(client);
        assertEquals("qwen3.8-27b", client.modelName());
    }
}
