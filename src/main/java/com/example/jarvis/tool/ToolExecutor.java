package com.example.jarvis.tool;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具执行器：根据模型返回的工具名称与参数，经 {@link ToolRegistry} 查找并执行对应工具。
 *
 * <p><b>安全原则</b>：模型只能"提出"工具调用请求，不能决定后端执行什么代码。
 * 允许执行的工具由 {@link ToolRegistry} 中登记的 {@link JarvisTool} Bean 白名单决定——
 * 模型传入未知工具名直接拒绝，不做任何反射式的"类名/方法名"动态执行。</p>
 *
 * <p><b>重构说明</b>：原先的 switch 分发已移除——工具定义（给模型的声明）与执行逻辑
 * 现在都收敛在各 {@link JarvisTool} 实现里，注册表统一管理，
 * 新增工具无需再改本类。</p>
 */
@Component
public class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public ToolExecutor(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据模型返回的工具名称和参数，执行对应工具。
     *
     * @param toolName  工具名称（模型返回的 tool_calls[].function.name）
     * @param arguments 工具参数（模型返回的 JSON 字符串）
     * @return 工具执行结果（字符串，后续作为 role=tool 消息发回模型）
     * @throws IllegalArgumentException 工具名未注册，或参数缺失/非法
     * @throws Exception                参数解析失败等
     */
    public String execute(String toolName, String arguments) throws Exception {
        JsonNode params = objectMapper.readTree(arguments);
        return toolRegistry.get(toolName).execute(params);
    }
}
