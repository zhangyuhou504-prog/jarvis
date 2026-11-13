package com.example.jarvis.tool;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 工具定义数据：名称、功能描述、参数 Schema（JSON Schema）。
 *
 * <p>纯数据载体 + 构建辅助：工具实现声明"我叫什么、干什么、参数是什么"，
 * {@link #toJson()} 负责转成 OpenAI 兼容的完整定义 JSON。
 * 各工具不再各自拼 JSON，定义结构由这里统一保证。</p>
 */
public record ToolDefinition(

        /** 工具名称（模型调用时的标识） */
        String name,

        /** 功能描述（模型据此决定何时调用该工具） */
        String description,

        /** 参数 Schema（JSON Schema 的 object 节点） */
        ObjectNode parameters
) {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * 构建完整的 OpenAI 兼容工具定义：
     * {@code {"type":"function","function":{"name":...,"description":...,"parameters":...}}}
     */
    public ObjectNode toJson() {
        ObjectNode function = JSON.createObjectNode();
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);
        ObjectNode tool = JSON.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }

    /**
     * 构建单个参数属性节点。
     *
     * @param type        JSON Schema 类型（integer / string / ...）
     * @param description 参数说明
     * @return 属性节点
     */
    public static ObjectNode property(String type, String description) {
        ObjectNode prop = JSON.createObjectNode();
        prop.put("type", type);
        prop.put("description", description);
        return prop;
    }

    /**
     * 创建空的 object 节点（用于组装 properties 集合）。
     */
    public static ObjectNode createNode() {
        return JSON.createObjectNode();
    }

    /**
     * 构建 object 类型的参数 Schema（properties + required，additionalProperties=false）。
     *
     * @param properties 属性集合
     * @param required   必填参数名（可为空列表）
     * @return 参数 Schema 节点
     */
    public static ObjectNode objectParameters(ObjectNode properties, List<String> required) {
        ObjectNode params = JSON.createObjectNode();
        params.put("type", "object");
        params.set("properties", properties);
        ArrayNode requiredNode = JSON.createArrayNode();
        required.forEach(requiredNode::add);
        params.set("required", requiredNode);
        params.put("additionalProperties", false);
        return params;
    }
}
