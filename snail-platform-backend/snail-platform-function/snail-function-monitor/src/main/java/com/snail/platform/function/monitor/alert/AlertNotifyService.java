package com.snail.platform.function.monitor.alert;

import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.function.monitor.alert.dto.AlertDetailVO;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlert;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlertRule;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.notify.api.NotifyApi;
import com.snail.platform.function.system.api.UserBriefPort;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:告警通知器——触发/恢复各发一次站内信(收件人=运维角色 role 3 启用成员),info 级只记不发;
 * 发送成败都追加到告警 notify_log(DD-08 手工序列化)。
 * 上游:AlertEvaluator(评估命中后调用)、AlertRuleService(停用/删除的恢复通知,afterCommit);
 * 下游:NotifyApi(notify 模块 api 包)、UserBriefPort(system api 包)。
 * 口径:定时/事务后线程无租户上下文——收件人解析与发送包在 TenantContextHolder.asTenant(1L,…) 内,
 * finally 由 asTenant 自行恢复(§4.3);tenantId=1(平台租户)、bizType=monitor、senderName=系统通知、
 * channels=inner(站内信+SSE);失败只记 error 不重试不影响告警状态;凭据与人名不进日志。
 */
@Service
public class AlertNotifyService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifyService.class);

    /** 平台租户 ID(V1 种子 default 即平台自身) */
    private static final long PLATFORM_TENANT_ID = 1L;

    /** 运维角色 ID(V5 种子 monitor_ops) */
    private static final long OPS_ROLE_ID = 3L;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotifyApi notifyApi;
    private final UserBriefPort userBriefPort;
    private final SysMonitorAlertMapper alertMapper;
    private final ObjectMapper objectMapper;

    public AlertNotifyService(NotifyApi notifyApi, UserBriefPort userBriefPort,
                              SysMonitorAlertMapper alertMapper, ObjectMapper objectMapper) {
        this.notifyApi = notifyApi;
        this.userBriefPort = userBriefPort;
        this.alertMapper = alertMapper;
        this.objectMapper = objectMapper;
    }

    /** 触发通知:firing;info 级只记 notify_log(suppressed)不发 */
    public void fire(SysMonitorAlertRule rule, SysMonitorAlert alert) {
        if ("info".equals(rule.getLevel())) {
            appendNotifyLog(alert, "fire", 0, List.of(), "suppressed:info 级不通知");
            return;
        }
        doSend(rule, alert, "fire", "触发");
    }

    /** 恢复通知:resolved;info 级同样只记不发 */
    public void resolve(SysMonitorAlertRule rule, SysMonitorAlert alert) {
        if ("info".equals(rule.getLevel())) {
            appendNotifyLog(alert, "resolve", 0, List.of(), "suppressed:info 级不通知");
            return;
        }
        doSend(rule, alert, "resolve", "恢复");
    }

    private void doSend(SysMonitorAlertRule rule, SysMonitorAlert alert, String type, String actionWord) {
        try {
            TenantContextHolder.asTenant(PLATFORM_TENANT_ID, () -> {
                List<Long> recipients = userBriefPort.enabledUserIdsOfRole(OPS_ROLE_ID);
                if (recipients.isEmpty()) {
                    appendNotifyLog(alert, type, 0, List.of("inner"), null);
                    return null;
                }
                String title = "[%s] %s %s".formatted(rule.getLevel(), rule.getName(), actionWord);
                String content = buildContent(rule, alert, actionWord);
                try {
                    notifyApi.send(new NotifyApi.NotifySendCommand(PLATFORM_TENANT_ID, title, content,
                            "monitor", alert.getAlertId(), null, "系统通知", recipients, Set.of("inner"), null));
                    appendNotifyLog(alert, type, recipients.size(), List.of("inner"), null);
                } catch (Exception e) {
                    log.warn("monitor 告警通知发送失败(alertId={}): {}", alert.getAlertId(), e.getMessage());
                    appendNotifyLog(alert, type, recipients.size(), List.of("inner"), e.getMessage());
                }
                return null;
            });
        } catch (Exception e) {
            // 通知链任何异常不影响告警状态流转
            log.warn("monitor 告警通知链失败(alertId={}): {}", alert.getAlertId(), e.getMessage());
        }
    }

    private String buildContent(SysMonitorAlertRule rule, SysMonitorAlert alert, String actionWord) {
        StringBuilder sb = new StringBuilder();
        sb.append("监控告警").append(actionWord).append("\n");
        sb.append("规则:").append(rule.getName()).append("\n");
        sb.append("指标:").append(rule.getMetric())
                .append(" ").append(rule.getComparator())
                .append(" ").append(rule.getThreshold()).append("\n");
        sb.append("当前值:").append(alert.getTriggerValue()).append("\n");
        sb.append("首次触发:").append(alert.getFirstTime() == null ? "-" : alert.getFirstTime().format(TIME_FMT)).append("\n");
        if ("恢复".equals(actionWord) && alert.getResolveTime() != null) {
            sb.append("恢复时间:").append(alert.getResolveTime().format(TIME_FMT)).append("\n");
        } else {
            sb.append("最近满足:").append(alert.getLastTime() == null ? "-" : alert.getLastTime().format(TIME_FMT)).append("\n");
        }
        sb.append("告警 ID:").append(alert.getAlertId());
        return sb.toString();
    }

    /** 追加一条通知记录到 alert.notify_log(读改写,单告警低频可接受) */
    private void appendNotifyLog(SysMonitorAlert alert, String type, int recipients, List<String> channels, String error) {
        try {
            String record = objectMapper.writeValueAsString(new AlertDetailVO.NotifyRecord(
                    LocalDateTime.now().withNano(0), type, recipients, channels, error));
            String existing = alert.getNotifyLog();
            String updated;
            if (existing == null || existing.isBlank()) {
                updated = "[" + record + "]";
            } else {
                updated = existing.substring(0, existing.length() - 1) + "," + record + "]";
            }
            alert.setNotifyLog(updated);
            SysMonitorAlert patch = new SysMonitorAlert();
            patch.setAlertId(alert.getAlertId());
            patch.setNotifyLog(updated);
            alertMapper.updateById(patch);
        } catch (Exception e) {
            log.warn("monitor notify_log 追加失败(alertId={}): {}", alert.getAlertId(), e.getMessage());
        }
    }
}
