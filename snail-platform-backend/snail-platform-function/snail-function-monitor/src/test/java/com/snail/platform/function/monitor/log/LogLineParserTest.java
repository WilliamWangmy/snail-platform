package com.snail.platform.function.monitor.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.function.monitor.log.LogLineParser.LogLine;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * 职责:日志行解析纯单测——冻结正则与 logback pattern 对齐、堆栈行返回 null、堆栈拼接 4000 上限(§7.2)。
 * 上游:LogLineParser 静态工具;下游:FileLogSource/LokiLogSource。
 */
class LogLineParserTest {

    private static final String LINE =
            "2026-11-16 10:00:00.123 INFO  [abc123] [http-nio-8080-exec-1] com.snail.app.Service - 业务处理完成";

    @Test
    void parsesStructuredLine() {
        LogLine line = LogLineParser.parse(LINE);
        assertNotNull(line);
        assertEquals(LocalDateTime.of(2026, 11, 16, 10, 0, 0, 123_000_000), line.time());
        assertEquals("INFO", line.level());
        assertEquals("abc123", line.traceId());
        assertEquals("http-nio-8080-exec-1", line.thread());
        assertEquals("com.snail.app.Service", line.logger());
        assertEquals("业务处理完成", line.message());
        assertEquals(LINE, line.raw());
    }

    @Test
    void emptyTraceIdBecomesEmptyString() {
        LogLine line = LogLineParser.parse(
                "2026-11-16 10:00:00.123 ERROR [] [main] com.snail.App - boom");
        assertNotNull(line);
        assertEquals("", line.traceId());
        assertEquals("ERROR", line.level());
    }

    @Test
    void stackLineReturnsNull() {
        assertNull(LogLineParser.parse("\tat com.snail.app.Service.run(Service.java:42)"));
        assertNull(LogLineParser.parse("java.lang.RuntimeException: boom"));
        assertNull(LogLineParser.parse(""));
    }

    @Test
    void badTimestampReturnsNull() {
        // 正则时间位匹配但日期非法 → 解析异常返回 null
        assertNull(LogLineParser.parse("9999-99-99 99:99:99.999 INFO [] [main] a.b.C - msg"));
    }

    @Test
    void appendStackMergesWithNewline() {
        String merged = LogLineParser.appendStack("boom", "\tat a.b.C:1");
        assertEquals("boom\n\tat a.b.C:1", merged);
    }

    @Test
    void appendStackCapsAtFourThousand() {
        String longMessage = "x".repeat(3990);
        String merged = LogLineParser.appendStack(longMessage, "\tat a:1"); // 3990 + 1 + 7 = 3998 未超
        assertEquals(3998, merged.length());

        String merged2 = LogLineParser.appendStack("x".repeat(4000), "\tat a:1");
        assertEquals(LogLineParser.MAX_MESSAGE_LENGTH + 1, merged2.length()); // 4000 + …
        assertTrue(merged2.endsWith("…"));
    }

    @Test
    void allLevelsParse() {
        for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"}) {
            LogLine line = LogLineParser.parse("2026-11-16 10:00:00.000 " + level + " [] [main] a.B - m");
            assertNotNull(line, level);
            assertEquals(level, line.level());
        }
    }
}
