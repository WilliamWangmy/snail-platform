package com.snail.platform.function.monitor.db.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:数据库面板 summary 出参契约(主方案 §5.1.1,冻结)——池/SQL/服务端三段 + slowTop 预览。
 */
public record DbSummaryVO(

        @Schema(description = "连接池读数(Hikari 实时)")
        Pool pool,

        @Schema(description = "慢 SQL 采集统计")
        SqlStats sql,

        @Schema(description = "MySQL 服务端最近快照(采样 tick 刷新)")
        Server server,

        @Schema(description = "慢 SQL Top(按 maxMs 降序,前 5)")
        List<SlowSqlDigestVO> slowTop,

        @Schema(description = "面板取数时间")
        LocalDateTime checkedAt) {

    /**
     * 连接池读数:available=false 时数值全 null(非 Hikari/池未启动)。
     */
    public record Pool(
            /** 是否可读(Hikari 且池已启动) */
            boolean available,
            /** 活跃连接 */
            Integer active,
            /** 空闲连接 */
            Integer idle,
            /** 总连接 */
            Integer total,
            /** 最大连接 */
            Integer max,
            /** 等待线程 */
            Integer pending,
            /** 使用率 %,1 位小数 */
            Double usagePercent) {
    }

    /**
     * 慢 SQL 统计:enabled=false 时计数为 null。
     */
    public record SqlStats(
            /** 采集开关 */
            boolean enabled,
            /** 最近 60s 执行速率(次/s) */
            Double execPerSec,
            /** 最近 60s 平均耗时(ms) */
            Double avgRtMs,
            /** 慢 SQL 阈值(ms) */
            Long slowThresholdMs,
            /** 最近 5 分钟慢 SQL 次数 */
            Long slowCount5m,
            /** 自启动累计慢 SQL 次数 */
            Long slowCountTotal,
            /** 采集器启动时间 */
            LocalDateTime since) {
    }

    /**
     * MySQL 服务端快照:available=false 时 lastError 说明原因(disabled=探针关闭)。
     */
    public record Server(
            /** 探测是否成功 */
            boolean available,
            /** MySQL 版本 */
            String version,
            /** max_connections */
            Long maxConnections,
            /** Threads_connected */
            Long threadsConnected,
            /** Threads_running */
            Long threadsRunning,
            /** 行锁等待折算(次/min) */
            Long rowLockWaitsPerMin,
            /** 最近错误 */
            String lastError,
            /** 快照时间 */
            LocalDateTime checkedAt) {
    }
}
