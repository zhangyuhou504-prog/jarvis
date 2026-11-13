package com.example.jarvis.tool;

import tools.jackson.databind.JsonNode;

/**
 * Jarvis 工具契约：模型可调用的任何工具都实现此接口。
 *
 * <p>不同工具的业务逻辑可以完全不同（本地计算、系统时间、SLS 日志查询、
 * 只读 SQL……），但都遵循同一契约：有名字、有定义（声明给模型）、可执行。</p>
 *
 * <p><b>定义与执行分离</b>：{@link #getDefinition()} 只是告诉模型"有这个工具、
 * 参数长什么样"，不会执行任何 Java 代码；真正的执行由 {@link #execute(JsonNode)}
 * 完成，且只能由后端白名单内的工具实例触发（经 {@link ToolRegistry} 查找）。</p>
 */
public interface JarvisTool {

    /**
     * 工具名称（与定义中的 name 字段一致，模型调用时的标识，注册表的主键）。
     */
    String getName();

    /**
     * OpenAI 兼容的工具定义 JSON（{@code {"type":"function","function":{...}}}），
     * 放入模型请求的 {@code tools} 字段。
     */
    JsonNode getDefinition();

    /**
     * 执行工具。
     *
     * @param arguments 模型返回的工具参数（JSON 对象，已由 ToolExecutor 解析）
     * @return 执行结果（字符串，作为 role=tool 消息回传给模型）
     * @throws IllegalArgumentException 参数缺失或非法
     */
    String execute(JsonNode arguments);
}
