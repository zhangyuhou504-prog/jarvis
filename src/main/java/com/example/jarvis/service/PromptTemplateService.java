package com.example.jarvis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Prompt 模板服务：模板注册表 + 渲染。
 *
 * <p><b>设计原则：上游只传模板标识（如 {@code java-tech}），不传具体变量。</b>
 * 每个模板的预设变量由 Jarvis 内部注册表维护——Prompt 是 Jarvis 的行为配置，
 * 不开放给调用方随意覆盖，避免上游通过变量注入覆盖行为约束。
 * 注意：System Prompt 本身不是安全边界，权限与工具调用仍由后端控制。</p>
 *
 * <p>模板文件位于 {@code classpath:prompts/}，变量使用 {@code ${varName}} 语法。
 * 渲染是纯 Java 侧字符串替换，模型只看到最终文本，不理解变量语法。</p>
 */
@Service
@Slf4j
public class PromptTemplateService {

    /**
     * 默认模板标识（不传 template 时使用）。
     */
    public static final String DEFAULT_TEMPLATE = "default";

    /**
     * 模板变量语法：{@code ${varName}}。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    /**
     * 模板注册表：模板标识 → 模板文件 + 预设变量。
     *
     * <p>新增模板时在此登记：模板文件放 {@code classpath:prompts/}，
     * 预设变量需覆盖模板声明的全部 {@code ${var}} 占位符。</p>
     */
    private static final Map<String, TemplateDefinition> REGISTRY = Map.of(
            "default", new TemplateDefinition("system-prompt.md", Map.of()),
            "java-tech", new TemplateDefinition("java-tech-template.md", Map.of(
                    "role", "Java 后端开发助手",
                    "task", "解答 Java 技术问题",
                    "rules", "结合 Java 代码解释原理，给出可落地的方案，并说明适用场景与优缺点")),
            "sql-analysis", new TemplateDefinition("sql-analysis-template.md", Map.of(
                    "role", "数据库性能分析助手",
                    "task", "分析 SQL 慢查询",
                    "rules", "重点分析执行计划、索引使用和查询优化建议，必要时给出改写示例"))
    );

    /**
     * 模板文件缓存：classpath 资源运行期不可变，读一次即可。
     */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    /**
     * 按模板标识渲染系统提示词（使用注册表中的预设变量）。
     *
     * @param templateName 模板标识（如 {@code java-tech}、{@code sql-analysis}）
     * @return 渲染后的完整 prompt
     * @throws IllegalArgumentException 模板标识未注册
     * @throws IllegalStateException    预设变量未覆盖模板声明的全部变量
     */
    public String render(String templateName) {
        TemplateDefinition definition = REGISTRY.get(templateName);
        if (definition == null) {
            throw new IllegalArgumentException("未知模板: " + templateName + "，可用模板: " + REGISTRY.keySet());
        }
        String template = loadTemplate(definition.file());

        // 1. 预设变量必须覆盖模板声明的全部变量（保证输出无未替换占位符）
        Set<String> declared = extractPlaceholders(template);
        Set<String> missing = declared.stream()
                .filter(v -> !definition.variables().containsKey(v))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("模板 " + templateName + " 的预设变量缺少: " + missing);
        }

        // 2. 替换
        String result = template;
        for (Map.Entry<String, String> entry : definition.variables().entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }

        // 3. 双保险：确认模板声明的占位符已全部替换（变量值里恰好含 ${...} 不算）
        Set<String> remaining = extractPlaceholders(result);
        remaining.retainAll(declared);
        if (!remaining.isEmpty()) {
            throw new IllegalStateException("模板 " + templateName + " 渲染后仍存在未替换变量: " + remaining);
        }
        return result;
    }

    /**
     * 列出所有可用模板标识。
     *
     * @return 模板标识集合
     */
    public Set<String> availableTemplates() {
        return REGISTRY.keySet();
    }

    /**
     * 加载模板文件（带缓存）。
     *
     * @param fileName 模板文件名
     * @return 模板原文
     */
    private String loadTemplate(String fileName) {
        return templateCache.computeIfAbsent(fileName, name -> {
            ClassPathResource resource = new ClassPathResource("prompts/" + name);
            if (!resource.exists()) {
                throw new IllegalStateException("模板文件不存在: prompts/" + name);
            }
            try {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("模板读取失败: prompts/" + name, e);
            }
        });
    }

    /**
     * 提取文本中所有 {@code ${varName}} 的变量名。
     *
     * @param text 待扫描文本
     * @return 变量名集合（保持出现顺序）
     */
    private Set<String> extractPlaceholders(String text) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * 模板定义：模板文件 + 预设变量。
     */
    private record TemplateDefinition(String file, Map<String, String> variables) {
    }
}
