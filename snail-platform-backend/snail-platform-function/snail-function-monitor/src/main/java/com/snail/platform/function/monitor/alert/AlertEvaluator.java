package com.snail.platform.function.monitor.alert;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlert;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlertRule;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertRuleMapper;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.MetricTickEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 职责:告警评估状态机——消费采样 tick(DD-05),按启用规则比较触发值,连续 durationPoints 点开新告警、
 * 连续 2 点不满足恢复;一规则同时最多一条开放告警(去重靠 DB 查询,不靠内存)。
 * 上游:MetricTickEvent(@Order(10) 先于数据源状态刷新);下游:SysMonitorAlertMapper(写)、
 * AlertNotifyService(通知)。
 * 口径:缺失样本不推进不按 0 处理(持续点数只统计有样本的 tick);静默期内不新开、不发任何通知(含恢复,
 * DD-16),开放告警照常更新/恢复;连续计数存内存、重启清零后按 DB 开放状态继续;整体异常吞掉下一 tick 自愈(§2.4);
 * 住 alert/ 根不进 .service——每 tick 至多一条单语句写,避免被「多写必须成事务」误判(DD-25)。
 */
@Component
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    /** 连续不满足多少点后恢复(冻结口径) */
    private static final int RESOLVE_STREAK = 2;

    private final MonitorProperties properties;
    private final SysMonitorAlertRuleMapper ruleMapper;
    private final SysMonitorAlertMapper alertMapper;
    private final AlertNotifyService notifyService;

    /** 规则连续命中/未命中计数:ruleId → streak;重启清零,去重靠 DB */
    private final ConcurrentHashMap<Long, Streak> streaks = new ConcurrentHashMap<>();

    /** 单规则连续计数 */
    private record Streak(int hit, int miss) {
    }

    public AlertEvaluator(MonitorProperties properties, SysMonitorAlertRuleMapper ruleMapper,
                          SysMonitorAlertMapper alertMapper, AlertNotifyService notifyService) {
        this.properties = properties;
        this.ruleMapper = ruleMapper;
        this.alertMapper = alertMapper;
        this.notifyService = notifyService;
    }

    /** 每 tick 评估一次;@Order(10):评估先于数据源状态刷新(DD-05) */
    @EventListener(MetricTickEvent.class)
    @Order(10)
    public void onTick(MetricTickEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            evaluate(event.values());
        } catch (Exception e) {
            log.error("monitor 告警评估失败: {}", e.getMessage());
        }
    }

    private void evaluate(Map<String, Double> values) {
        // 只评估启用规则;不做规则缓存,规则修改下一 tick 生效
        List<SysMonitorAlertRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<SysMonitorAlertRule>()
                .eq(SysMonitorAlertRule::getEnabled, 0));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (SysMonitorAlertRule rule : rules) {
            try {
                evaluateRule(rule, values.get(rule.getMetric()), now);
            } catch (Exception e) {
                log.warn("monitor 规则评估失败(ruleId={}): {}", rule.getRuleId(), e.getMessage());
            }
        }
    }

    private void evaluateRule(SysMonitorAlertRule rule, Double value, LocalDateTime now) {
        if (value == null) {
            // 缺失样本不推进:计数不动,开放告警不更新
            return;
        }
        Streak current = streaks.getOrDefault(rule.getRuleId(), new Streak(0, 0));
        boolean hit = compare(value, rule.getComparator(), rule.getThreshold());
        boolean silenced = rule.getSilenceUntil() != null && now.isBefore(rule.getSilenceUntil());
        SysMonitorAlert open = alertMapper.selectOpenByRuleId(rule.getRuleId());
        if (hit) {
            if (open == null) {
                if (current.hit() + 1 >= rule.getDurationPoints() && !silenced) {
                    openAlert(rule, value, now);
                }
                streaks.put(rule.getRuleId(), new Streak(current.hit() + 1, 0));
            } else {
                // 开放告警:刷新最近满足时间与触发值
                SysMonitorAlert patch = new SysMonitorAlert();
                patch.setAlertId(open.getAlertId());
                patch.setLastTime(now);
                patch.setTriggerValue(value);
                alertMapper.updateById(patch);
                streaks.put(rule.getRuleId(), new Streak(current.hit() + 1, 0));
            }
        } else {
            streaks.put(rule.getRuleId(), new Streak(0, current.miss() + 1));
            if (open != null && current.miss() + 1 >= RESOLVE_STREAK) {
                resolveAlert(rule, open, now, silenced);
                streaks.put(rule.getRuleId(), new Streak(0, 0));
            }
        }
    }

    /** 开新告警(firing)+ 触发通知(静默期在调用侧已拦) */
    private void openAlert(SysMonitorAlertRule rule, double value, LocalDateTime now) {
        SysMonitorAlert alert = new SysMonitorAlert();
        alert.setRuleId(rule.getRuleId());
        alert.setRuleName(rule.getName());
        alert.setMetric(rule.getMetric());
        alert.setLevel(rule.getLevel());
        alert.setStatus("firing");
        alert.setTriggerValue(value);
        alert.setThreshold(rule.getThreshold());
        alert.setComparator(rule.getComparator());
        alert.setFirstTime(now);
        alert.setLastTime(now);
        alertMapper.insert(alert);
        notifyService.fire(rule, alert);
    }

    /** 恢复:置 resolved;恢复通知只在真正从开放态流转时发一次(静默期不发,DD-16) */
    private void resolveAlert(SysMonitorAlertRule rule, SysMonitorAlert open, LocalDateTime now, boolean silenced) {
        SysMonitorAlert patch = new SysMonitorAlert();
        patch.setAlertId(open.getAlertId());
        patch.setStatus("resolved");
        patch.setResolveTime(now);
        alertMapper.updateById(patch);
        if (!silenced) {
            open.setResolveTime(now);
            notifyService.resolve(rule, open);
        }
    }

    /** 比较器:gt/gte/lt/lte/eq */
    static boolean compare(double value, String comparator, double threshold) {
        return switch (comparator) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            case "eq" -> value == threshold;
            default -> false;
        };
    }
}
