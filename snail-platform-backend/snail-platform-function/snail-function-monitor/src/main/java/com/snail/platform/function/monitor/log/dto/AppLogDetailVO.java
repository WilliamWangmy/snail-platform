package com.snail.platform.function.monitor.log.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:日志行上下文出参契约(主方案 §5.3,冻结)——target/before/after 均为已解析结构(原始行文本在 message)。
 */
@Schema(description = "日志行上下文")
public record AppLogDetailVO(

        @Schema(description = "目标行")
        LogLineVO target,

        @Schema(description = "目标行之前的上下文(正序)")
        List<LogLineVO> before,

        @Schema(description = "目标行之后的上下文(正序)")
        List<LogLineVO> after) {
}
