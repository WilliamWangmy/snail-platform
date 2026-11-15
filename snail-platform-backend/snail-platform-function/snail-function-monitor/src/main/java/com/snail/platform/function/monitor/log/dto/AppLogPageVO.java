package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:日志检索出参契约(主方案 §5.3,冻结)——rows 时间倒序(窗口内最后 limit 条),
 * scannedLines 为实际扫描行数(file 模式)/0(loki),truncated 表达双上限截停。
 */
@Schema(description = "应用日志检索结果")
public record AppLogPageVO(

        @Schema(description = "实际模式:file / loki")
        String source,

        @Schema(description = "行列表(时间倒序)")
        List<LogLineVO> rows,

        @Schema(description = "实际扫描行数(loki 模式为 0)")
        long scannedLines,

        @Schema(description = "是否因扫描行数/耗时上限截停(true=请缩小时间窗)")
        boolean truncated) {
}
