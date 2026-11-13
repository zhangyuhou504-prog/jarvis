package com.example.jarvis.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：启动时收集所有 {@link JarvisTool} Bean，按名称注册。
 *
 * <p><b>统一管理两个出口</b>：</p>
 * <ul>
 *   <li>定义出口：{@link #definitions()} 供 ToolLoopService 放入模型请求的 tools 字段</li>
 *   <li>执行入口：{@link #get(String)} 供 ToolExecutor 按名称查找工具执行</li>
 * </ul>
 *
 * <p>新增工具只需新建一个 {@code @Component} 实现 {@link JarvisTool}，
 * 无需再改 ToolLoopService / ToolExecutor——定义与执行天然一致（同一个 Bean）。</p>
 *
 * <p><b>安全原则不变</b>：模型只能调用注册表里登记过的工具名，
 * 未知名称直接拒绝，不存在反射执行任意类名的路径。</p>
 */
@Component
@Slf4j
public class ToolRegistry {

    private static final JsonMapper JSON = new JsonMapper();

    /**
     * 工具名 → 工具实例（保持 Bean 定义顺序，tools 数组顺序稳定）。
     */
    private final Map<String, JarvisTool> tools;

    /**
     * 构造时 Spring 注入所有 JarvisTool Bean，按名称注册。
     * 重名直接启动失败，避免运行时歧义。
     */
    public ToolRegistry(List<JarvisTool> toolBeans) {
        Map<String, JarvisTool> map = new LinkedHashMap<>();
        for (JarvisTool tool : toolBeans) {
            JarvisTool existing = map.putIfAbsent(tool.getName(), tool);
            if (existing != null) {
                throw new IllegalStateException("工具名重复: " + tool.getName());
            }
        }
        this.tools = java.util.Collections.unmodifiableMap(map);
        log.info("工具注册表初始化完成: {}", tools.keySet());
    }

    /**
     * 按名称查找工具（执行入口）。
     *
     * @param toolName 工具名称
     * @return 工具实例
     * @throws IllegalArgumentException 工具名未注册
     */
    public JarvisTool get(String toolName) {
        JarvisTool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("不支持的工具：" + toolName);
        }
        return tool;
    }

    /**
     * 所有工具定义（每次调用返回新数组，供单轮请求使用）。
     */
    public ArrayNode definitions() {
        ArrayNode array = JSON.createArrayNode();
        tools.values().forEach(tool -> array.add(tool.getDefinition()));
        return array;
    }
}
