package com.snail.platform.function.monitor.db.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:/monitor/db/slow 出参契约(主方案 §5.1.1,冻结)——thresholdMs/since/top(≤20)/recent(≤50)。
 */
public record SlowSqlQueryVO(

        @Schema(description = "慢 SQL 阈值(ms)")
        long thresholdMs,

        @Schema(description = "采集器启动时间")
        LocalDateTime since,

        @Schema(description = "慢 SQL Top(按 maxMs 降序,前 20)")
        List<SlowSqlDigestVO> top,

        @Schema(description = "最近慢 SQL(时间倒序,前 50,带 traceId)")
        List<SlowSqlEventVO> recent) {
}
