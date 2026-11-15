package com.snail.platform.function.monitor.db.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:最近慢 SQL 事件出参契约(主方案 §5.1.1 recent 元素,冻结)。
 */
public record SlowSqlEventVO(

        @Schema(description = "执行时间")
        LocalDateTime time,

        @Schema(description = "耗时(ms)")
        long costMs,

        @Schema(description = "摘要 ID")
        String digestId,

        @Schema(description = "归一化 SQL 文本")
        String sql,

        @Schema(description = "mapper 全限定 ID")
        String mapperId,

        @Schema(description = "链路 ID(可跳日志页);调度线程为 null")
        String traceId) {
}
