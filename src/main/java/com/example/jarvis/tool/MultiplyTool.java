package com.example.jarvis.tool;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.util.List;

/**
 * 乘法工具：两个整数相乘。
 *
 * <p>使用 {@link BigInteger} 而非 {@code long}：模型传来的参数不应被无条件信任，
 * 大整数相乘在 long 范围内可能溢出，BigInteger 避免该问题。</p>
 *
 * <p>实现 {@link JarvisTool}：定义（声明给模型）与执行（真正的 Java 逻辑）
 * 收敛在同一个类，由 {@link ToolRegistry} 统一注册。</p>
 */
@Component
public class MultiplyTool implements JarvisTool {

    private static final String NAME = "multiply";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public JsonNode getDefinition() {
        ObjectNode properties = ToolDefinition.createNode();
        properties.set("a", ToolDefinition.property("integer", "第一个乘数"));
        properties.set("b", ToolDefinition.property("integer", "第二个乘数"));
        return new ToolDefinition(
                NAME,
                "计算两个整数的乘积。当用户要求做乘法、两个数相乘时使用。",
                ToolDefinition.objectParameters(properties, List.of("a", "b"))
        ).toJson();
    }

    @Override
    public String execute(JsonNode arguments) {
        BigInteger a = new BigInteger(arguments.required("a").asText());
        BigInteger b = new BigInteger(arguments.required("b").asText());
        return multiply(a, b).toString();
    }

    /**
     * 两个整数相乘。
     *
     * @param a 乘数
     * @param b 乘数
     * @return 乘积
     * @throws IllegalArgumentException 任一乘数为 null
     */
    public BigInteger multiply(BigInteger a, BigInteger b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("乘数不能为空");
        }

        return a.multiply(b);
    }
}
