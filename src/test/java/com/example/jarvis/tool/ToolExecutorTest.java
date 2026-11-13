package com.example.jarvis.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolExecutorTest {

    private ToolExecutor executor;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        CurrentTimeTool currentTimeTool = new CurrentTimeTool();
        ReflectionTestUtils.setField(currentTimeTool, "defaultTimezone", "Asia/Shanghai");
        registry = new ToolRegistry(List.of(new MultiplyTool(), currentTimeTool));
        executor = new ToolExecutor(registry, new ObjectMapper());
    }

    @Test
    void shouldExecuteMultiplyTool() throws Exception {
        String result = executor.execute("multiply", "{\"a\":128,\"b\":256}");
        assertEquals("32768", result);
    }

    @Test
    void shouldExecuteGetCurrentTimeWithTimezone() throws Exception {
        String result = executor.execute("getCurrentTime", "{\"timezone\":\"Asia/Shanghai\"}");

        // ISO_OFFSET_DATE_TIME 格式：yyyy-MM-ddTHH:mm:ss 开头，+08:00 结尾
        org.junit.jupiter.api.Assertions.assertTrue(
                result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\\+08:00"),
                "实际返回: " + result
        );
    }

    @Test
    void shouldUseDefaultTimezoneWhenMissing() throws Exception {
        String result = executor.execute("getCurrentTime", "{}");
        assertEquals("+08:00", result.substring(result.length() - 6));
    }

    @Test
    void shouldRejectInvalidTimezone() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("getCurrentTime", "{\"timezone\":\"Mars/Olympus\"}"));
    }

    @Test
    void shouldRejectUnregisteredTool() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute("not_exist_tool", "{}"));
    }

    @Test
    void registryShouldExposeBothDefinitions() {
        var defs = registry.definitions();
        assertEquals(2, defs.size());
        assertEquals("multiply", defs.get(0).path("function").path("name").asText());
        assertEquals("getCurrentTime", defs.get(1).path("function").path("name").asText());
    }
}
