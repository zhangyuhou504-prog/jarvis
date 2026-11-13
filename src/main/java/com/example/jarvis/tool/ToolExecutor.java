package com.example.jarvis.tool;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;

/**
 * 工具执行器：根据模型返回的工具名称与参数，分发并执行对应的 Java 工具。
 *
 * <p><b>安全原则</b>：模型只能"提出"工具调用请求，不能决定后端执行什么代码。
 * 允许执行的工具由本类的 switch 白名单显式维护——模型传入未知工具名直接拒绝，
 * 不做任何反射式的"类名/方法名"动态执行。</p>
 *
 * <p>目前工具只有一个，直接 switch 分发最易理解；工具数量增加后再抽象注册中心。</p>
 *
 * <p><b>为什么用 tools.jackson（Jackson 3）</b>：Spring Boot 4 自动装配的
 * {@link ObjectMapper} 是 Jackson 3 的（Web 层序列化同样走它）；
 * classpath 上虽有 Jackson 2（langchain4j/spring-ai 传递依赖），但它没有
 * 对应的 Spring Bean，注入会启动失败。{@code JsonNode.required()} 也是
 * Jackson 3 的 API。</p>
 */
@Component
public class ToolExecutor {

    private final MultiplyTool multiplyTool;
    private final CurrentTimeTool currentTimeTool;
    private final ObjectMapper objectMapper;

    public ToolExecutor(
            MultiplyTool multiplyTool,
            CurrentTimeTool currentTimeTool,
            ObjectMapper objectMapper) {
        this.multiplyTool = multiplyTool;
        this.currentTimeTool = currentTimeTool;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据模型返回的工具名称和参数，执行对应工具。
     *
     * @param toolName  工具名称（模型返回的 tool_calls[].function.name）
     * @param arguments 工具参数（模型返回的 JSON 字符串）
     * @return 工具执行结果（字符串，后续作为 role=tool 消息发回模型）
     * @throws IllegalArgumentException 工具名不在白名单内，或参数缺失/非法
     * @throws Exception                参数解析失败等
     */
    public String execute(String toolName, String arguments) throws Exception {

        return switch (toolName) {
            case "multiply" -> {
                JsonNode params = objectMapper.readTree(arguments);

                BigInteger a = new BigInteger(
                        params.required("a").asText()
                );

                BigInteger b = new BigInteger(
                        params.required("b").asText()
                );

                yield multiplyTool.multiply(a, b).toString();
            }
            case "getCurrentTime" -> {
                JsonNode params = objectMapper.readTree(arguments);
                // timezone 非必填：缺失或 null 时传 null，由工具内部回落默认时区
                JsonNode tz = params.path("timezone");
                String timezone = (tz.isMissingNode() || tz.isNull()) ? null : tz.asText();
                yield currentTimeTool.getCurrentTime(timezone);
            }
            default -> throw new IllegalArgumentException(
                    "不支持的工具：" + toolName
            );
        };
    }
}
