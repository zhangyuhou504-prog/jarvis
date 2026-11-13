package com.example.jarvis.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 当前时间工具：获取指定时区的当前时间。
 *
 * <p><b>为什么显式指定 ZoneId 而非 {@code LocalDateTime.now()}</b>：
 * 服务器部署时区不一定是中国时区，显式指定才能保证结果准确；
 * 未传时区时使用配置项 {@code jarvis.tool.default-timezone}（默认 Asia/Shanghai）。</p>
 *
 * <p>返回 ISO 格式（含时区偏移量），例如 {@code 2026-11-13T14:30:00+08:00}，
 * 模型可据此向用户表述。</p>
 *
 * <p>实现 {@link JarvisTool}：定义与执行收敛在同一个类，由 {@link ToolRegistry} 统一注册。</p>
 */
@Component
public class CurrentTimeTool implements JarvisTool {

    private static final String NAME = "getCurrentTime";

    /**
     * 默认时区（用户未指定时区时使用）。
     */
    @Value("${jarvis.tool.default-timezone:Asia/Shanghai}")
    private String defaultTimezone;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public JsonNode getDefinition() {
        ObjectNode properties = ToolDefinition.createNode();
        properties.set("timezone", ToolDefinition.property("string",
                "IANA 时区，例如 Asia/Shanghai、America/New_York。不传则使用默认时区"));
        return new ToolDefinition(
                NAME,
                "获取指定时区的当前时间。当用户询问现在几点、某地当前时间时使用。",
                ToolDefinition.objectParameters(properties, List.of())
        ).toJson();
    }

    @Override
    public String execute(JsonNode arguments) {
        // timezone 非必填：缺失或 null 时传 null，回落默认时区
        JsonNode tz = arguments.path("timezone");
        String timezone = (tz.isMissingNode() || tz.isNull()) ? null : tz.asText();
        return getCurrentTime(timezone);
    }

    /**
     * 获取指定时区的当前时间。
     *
     * @param timezone IANA 时区（如 Asia/Shanghai、America/New_York）；
     *                 为 null 或空白时使用默认时区
     * @return ISO_OFFSET_DATE_TIME 格式的当前时间
     * @throws IllegalArgumentException 时区标识非法
     */
    public String getCurrentTime(String timezone) {
        try {
            String zone = (timezone == null || timezone.isBlank())
                    ? defaultTimezone
                    : timezone;

            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zone));

            return now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        } catch (DateTimeException e) {
            throw new IllegalArgumentException("不支持的时区：" + timezone);
        }
    }
}
