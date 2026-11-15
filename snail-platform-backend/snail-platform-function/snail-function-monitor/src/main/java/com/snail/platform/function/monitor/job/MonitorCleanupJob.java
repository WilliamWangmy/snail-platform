package com.snail.platform.function.monitor.job;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.job.service.MonitorCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 职责:清理调度入口——每日 03:30(cron 可配)触发三表分批清理。
 * 上游:@Scheduled 调度;下游:MonitorCleanupService(执行器,批间提交)。
 * 口径:首行总闸短路(DD-01);整体 try-catch 防调度停摆(§2.4);住 job/ 根只做调度不做事务(DD-25)。
 */
@Component
public class MonitorCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MonitorCleanupJob.class);

    private final MonitorProperties properties;
    private final MonitorCleanupService cleanupService;

    public MonitorCleanupJob(MonitorProperties properties, MonitorCleanupService cleanupService) {
        this.properties = properties;
        this.cleanupService = cleanupService;
    }

    /** 每日 03:30 清理(cron 来自 snail.monitor.cleanup.cron) */
    @Scheduled(cron = "${snail.monitor.cleanup.cron:0 30 3 * * ?}")
    public void clean() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            cleanupService.clean();
        } catch (Exception e) {
            log.error("monitor 清理任务失败: {}", e.getMessage());
        }
    }
}
