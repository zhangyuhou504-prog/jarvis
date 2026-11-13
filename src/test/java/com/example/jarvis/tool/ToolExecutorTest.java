package com.example.jarvis.tool;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolExecutorTest {

    private ToolExecutor newExecutor() {
        CurrentTimeTool currentTimeTool = new CurrentTimeTool();
        ReflectionTestUtils.setField(currentTimeTool, "defaultTimezone", "Asia/Shanghai");
        return new ToolExecutor(new MultiplyTool(), currentTimeTool, new ObjectMapper());
    }

    @Test
    void shouldExecuteMultiplyTool() throws Exception {
        ToolExecutor executor = newExecutor();

        String result = executor.execute(
                "multiply",
                "{\"a\":128,\"b\":256}"
        );

        assertEquals("32768", result);
    }

    @Test
    void shouldExecuteGetCurrentTimeWithTimezone() throws Exception {
        ToolExecutor executor = newExecutor();

        String result = executor.execute(
                "getCurrentTime",
                "{\"timezone\":\"Asia/Shanghai\"}"
        );

        // ISO_OFFSET_DATE_TIME 格式：yyyy-MM-ddTHH:mm:ss 开头，+08:00 结尾
        org.junit.jupiter.api.Assertions.assertTrue(
                result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*\\+08:00"),
                "实际返回: " + result
        );
    }

    @Test
    void shouldUseDefaultTimezoneWhenMissing() throws Exception {
        ToolExecutor executor = newExecutor();

        String result = executor.execute("getCurrentTime", "{}");

        assertEquals("+08:00", result.substring(result.length() - 6));
    }

    @Test
    void shouldRejectInvalidTimezone() throws Exception {
        ToolExecutor executor = newExecutor();

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute("getCurrentTime", "{\"timezone\":\"Mars/Olympus\"}")
        );
    }
}
