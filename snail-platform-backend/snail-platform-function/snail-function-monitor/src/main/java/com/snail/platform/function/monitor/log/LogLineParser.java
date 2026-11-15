package com.snail.platform.function.monitor.log;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 职责:应用日志行解析器——按 logback pattern 固定正则解析单行,堆栈行(非时间戳开头)由数据源
 * 追加到上一条命中的 message。
 * 上游:FileLogSource/LokiLogSource(流式喂数);下游:AppLogService(过滤与出参)。
 * 口径:正则与 logback pattern 逐段对齐(时间/级别/[traceId]/[thread]/logger/消息);
 * 单条 message 上限 4000 字符,超出截断并标 …;非法行返回 null 由数据源当堆栈行处理。
 */
public final class LogLineParser {

    private LogLineParser() {
    }

    /** 单条消息长度上限 */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    /** logback pattern 对齐的正则(冻结) */
    private static final Pattern LINE = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) +(TRACE|DEBUG|INFO|WARN|ERROR) "
                    + "+\\[([^\\]]*)\\] +\\[([^\\]]*)\\] +(\\S+) - (.*)$");

    /** pattern 时间格式:yyyy-MM-dd HH:mm:ss.SSS */
    public static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 解析后的日志行:fileId/lineNo 由数据源补充。
     */
    public record LogLine(
            /** 文件 ID(=文件名) */
            String fileId,
            /** 文件内行号(1 起) */
            long lineNo,
            /** 行时间 */
            LocalDateTime time,
            /** 级别:TRACE/DEBUG/INFO/WARN/ERROR */
            String level,
            /** 链路 ID(可空) */
            String traceId,
            /** 线程名 */
            String thread,
            /** logger 名(≤36 列宽) */
            String logger,
            /** 消息(堆栈行以 \n 连接,上限 4000 字符) */
            String message,
            /** 原始行(堆栈行拼接后的完整文本) */
            String raw) {
    }

    /** 解析单行;非结构化行(堆栈)返回 null,由数据源拼接到上一条 */
    public static LogLine parse(String raw) {
        Matcher matcher = LINE.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        try {
            LocalDateTime time = LocalDateTime.parse(matcher.group(1), TIME_FMT);
            return new LogLine(null, 0, time, matcher.group(2), emptyIfNull(matcher.group(3)),
                    matcher.group(4), matcher.group(5), matcher.group(6), raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** 堆栈行拼接:追加到上一条 message(带原始行),上限 4000 字符 */
    public static String appendStack(String message, String stackLine) {
        String merged = message + "\n" + stackLine;
        if (merged.length() > MAX_MESSAGE_LENGTH) {
            return merged.substring(0, MAX_MESSAGE_LENGTH) + "…";
        }
        return merged;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
