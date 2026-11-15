package com.snail.platform.function.monitor.job.service;

import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.mapper.SysMonitorMetricMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 职责:监控三表清理执行器——指标(7 天)/告警(只删 resolved 且过期)/报告(30 天)分批删除。
 * 上游:MonitorCleanupJob(03:30 触发);下游:三个 mapper 的 deleteExpiredBefore。
 * 口径:@Delete + 绑定参数 LIMIT(DD-19,不扩 .last() 白名单);每表一个独立 cleanup 方法、
 * 方法内只有一处 mapper 写调用点(循环执行同一条语句),不挂 @Transactional——批间各自提交,
 * 避免长事务锁表,也不触发「多写必须成事务」规则;总闸关闭时空转(清理无独立开关)。
 */
@Service
public class MonitorCleanupService {

    private static final Logger log = LoggerFactory.getLogger(MonitorCleanupService.class);

    private final MonitorProperties properties;
    private final SysMonitorMetricMapper metricMapper;
    private final SysMonitorAlertMapper alertMapper;
    private final com.snail.platform.function.monitor.diag.mapper.SysMonitorDiagReportMapper reportMapper;

    public MonitorCleanupService(MonitorProperties properties, SysMonitorMetricMapper metricMapper,
                                 SysMonitorAlertMapper alertMapper,
                                 com.snail.platform.function.monitor.diag.mapper.SysMonitorDiagReportMapper reportMapper) {
        this.properties = properties;
        this.metricMapper = metricMapper;
        this.alertMapper = alertMapper;
        this.reportMapper = reportMapper;
    }

    /** 清理入口:三表顺序执行,单表异常不影响其余表 */
    public void clean() {
        if (!properties.isEnabled()) {
            return;
        }
        int batchSize = properties.getCleanup().getBatchSize();
        int maxBatches = properties.getCleanup().getMaxBatches();
        cleanMetrics(batchSize, maxBatches);
        cleanAlerts(batchSize, maxBatches);
        cleanReports(batchSize, maxBatches);
    }

    /** 指标清理:保留 N 天,分批循环 */
    public void cleanMetrics(int batchSize, int maxBatches) {
        LocalDateTime before = LocalDateTime.now().withNano(0)
                .minusDays(properties.getCleanup().getMetricRetentionDays());
        int total = batchDelete(limit -> metricMapper.deleteExpiredBefore(before, limit), batchSize, maxBatches);
        log.info("monitor 清理过期指标 {} 行(before={})", total, before);
    }

    /** 告警清理:只删 resolved 且过期的行;firing/acked 永不删 */
    public void cleanAlerts(int batchSize, int maxBatches) {
        LocalDateTime before = LocalDateTime.now().withNano(0)
                .minusDays(properties.getCleanup().getAlertRetentionDays());
        int total = batchDelete(limit -> alertMapper.deleteResolvedBefore(before, limit), batchSize, maxBatches);
        log.info("monitor 清理过期告警 {} 行(before={})", total, before);
    }

    /** 报告清理:保留 N 天,分批循环 */
    public void cleanReports(int batchSize, int maxBatches) {
        LocalDateTime before = LocalDateTime.now().withNano(0)
                .minusDays(properties.getCleanup().getReportRetentionDays());
        int total = batchDelete(limit -> reportMapper.deleteExpiredBefore(before, limit), batchSize, maxBatches);
        log.info("monitor 清理过期诊断报告 {} 行(before={})", total, before);
    }

    /** 分批循环执行同一条删除语句;单批 0 行即停;最多 maxBatches 批 */
    private int batchDelete(java.util.function.IntUnaryOperator deleteOnce, int batchSize, int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = deleteOnce.applyAsInt(batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
