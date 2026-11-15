package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:日志行出参契约(主方案 §5.3 rows 元素,冻结);消息含堆栈(\n 连接)。
 */
@Schema(description = "应用日志行")
public record LogLineVO(

        @Schema(description = "文件 ID")
        String fileId,

        @Schema(description = "文件内行号(1 起)")
        long lineNo,

        @Schema(description = "行时间(ISO)")
        LocalDateTime time,

        @Schema(description = "级别")
        String level,

        @Schema(description = "链路 ID")
        String traceId,

        @Schema(description = "线程名")
        String thread,

        @Schema(description = "logger 名")
        String logger,

        @Schema(description = "消息(堆栈以换行连接,上限 4000 字符)")
        String message) {

    /** 由解析行转出参(fileId/lineNo 已由数据源补充) */
    public static LogLineVO from(com.snail.platform.function.monitor.log.LogLineParser.LogLine line) {
        return new LogLineVO(line.fileId(), line.lineNo(), line.time(), line.level(), line.traceId(),
                line.thread(), line.logger(), line.message());
    }
}
