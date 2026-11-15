package com.snail.platform.function.monitor.db.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:慢 SQL 摘要行出参契约(主方案 §5.1.1 slowTop/slow 元素,冻结)。
 */
public record SlowSqlDigestVO(

        @Schema(description = "摘要 ID(归一化 SQL SHA-256 前 8 位)")
        String digestId,

        @Schema(description = "归一化 SQL 文本(无参数值)")
        String sql,

        @Schema(description = "mapper 全限定 ID")
        String mapperId,

        @Schema(description = "慢命中次数")
        long slowCount,

        @Schema(description = "最大耗时(ms)")
        long maxMs,

        @Schema(description = "平均耗时(ms)")
        long avgMs,

        @Schema(description = "最近一次耗时(ms)")
        long lastMs,

        @Schema(description = "最近一次命中时间")
        LocalDateTime lastTime) {
}
