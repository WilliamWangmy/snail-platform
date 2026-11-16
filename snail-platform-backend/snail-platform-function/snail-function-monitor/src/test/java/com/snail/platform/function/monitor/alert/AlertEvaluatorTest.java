package com.snail.platform.function.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.snail.platform.function.monitor.alert.entity.SysMonitorAlert;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlertRule;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertRuleMapper;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.MetricTickEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 职责:告警评估状态机纯单测——四象限(触发/恢复/去重/静默)+ 缺失样本不推进 + 比较器(§6.7 验收)。
 * 上游:AlertEvaluator;下游:Mockito 替身 ruleMapper/alertMapper/notifyService,不连库、不发通知。
 */
class AlertEvaluatorTest {

    private final MonitorProperties properties = new MonitorProperties();
    private final SysMonitorAlertRuleMapper ruleMapper = mock(SysMonitorAlertRuleMapper.class);
    private final SysMonitorAlertMapper alertMapper = mock(SysMonitorAlertMapper.class);
    private final AlertNotifyService notifyService = mock(AlertNotifyService.class);

    private final AlertEvaluator evaluator =
            new AlertEvaluator(properties, ruleMapper, alertMapper, notifyService);

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
    }

    private SysMonitorAlertRule rule(long id, double threshold, String comparator, int durationPoints) {
        SysMonitorAlertRule rule = new SysMonitorAlertRule();
        rule.setRuleId(id);
        rule.setName("CPU 使用率过高");
        rule.setMetric("system.cpu.usage");
        rule.setComparator(comparator);
        rule.setThreshold(threshold);
        rule.setDurationPoints(durationPoints);
        rule.setLevel("warning");
        rule.setEnabled(0); // 0 = 启用
        return rule;
    }

    private MetricTickEvent tick(Map<String, Double> values) {
        return new MetricTickEvent(System.currentTimeMillis(), values, false);
    }

    private void tickValues(Map<String, Double> values) {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(1L, 85, "gt", 3)));
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(null);
        evaluator.onTick(tick(values));
    }

    @Test
    void opensAlertOnlyAfterDurationPointsConsecutiveHits() {
        for (int i = 0; i < 2; i++) {
            tickValues(Map.of("system.cpu.usage", 90.0));
        }
        verify(alertMapper, never()).insert(any(SysMonitorAlert.class));
        verify(notifyService, never()).fire(any(), any());

        tickValues(Map.of("system.cpu.usage", 91.0));
        ArgumentCaptor<SysMonitorAlert> captor = ArgumentCaptor.forClass(SysMonitorAlert.class);
        verify(alertMapper, times(1)).insert(captor.capture());
        assertEquals("firing", captor.getValue().getStatus());
        assertEquals("system.cpu.usage", captor.getValue().getMetric());
        assertEquals(85.0, captor.getValue().getThreshold());
        verify(notifyService, times(1)).fire(any(), any());
    }

    @Test
    void continuesOpenAlertInsteadOfDuplicateInsert() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(1L, 85, "gt", 1)));
        SysMonitorAlert open = new SysMonitorAlert();
        open.setAlertId(100L);
        open.setStatus("firing");
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(open);

        evaluator.onTick(tick(Map.of("system.cpu.usage", 90.0)));
        evaluator.onTick(tick(Map.of("system.cpu.usage", 92.0)));

        // 去重:不重复 insert,只 updateById 刷新最近满足时间与触发值(§6.1)
        verify(alertMapper, never()).insert(any(SysMonitorAlert.class));
        verify(alertMapper, times(2)).updateById(any(SysMonitorAlert.class));
        verify(notifyService, never()).fire(any(), any());
    }

    @Test
    void resolvesAfterTwoConsecutiveMisses() {
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(1L, 85, "gt", 1)));
        SysMonitorAlert open = new SysMonitorAlert();
        open.setAlertId(100L);
        open.setStatus("firing");
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(open);

        evaluator.onTick(tick(Map.of("system.cpu.usage", 20.0)));
        // 第 1 个 miss:不恢复
        verify(alertMapper, never()).updateById(any(SysMonitorAlert.class));
        verify(notifyService, never()).resolve(any(), any());

        evaluator.onTick(tick(Map.of("system.cpu.usage", 21.0)));
        // 第 2 个 miss:resolved + 恢复通知一次
        ArgumentCaptor<SysMonitorAlert> captor = ArgumentCaptor.forClass(SysMonitorAlert.class);
        verify(alertMapper, times(1)).updateById(captor.capture());
        assertEquals("resolved", captor.getValue().getStatus());
        assertEquals(100L, captor.getValue().getAlertId());
        verify(notifyService, times(1)).resolve(any(), any());
    }

    @Test
    void silenceBlocksNewAlertButAlertStateStillUpdates() {
        SysMonitorAlertRule silenced = rule(1L, 85, "gt", 1);
        silenced.setSilenceUntil(LocalDateTime.now().plusMinutes(30));
        when(ruleMapper.selectList(any())).thenReturn(List.of(silenced));
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(null);

        evaluator.onTick(tick(Map.of("system.cpu.usage", 90.0)));

        // 静默期:命中达标也不开新告警、不发通知(DD-16 完全静默)
        verify(alertMapper, never()).insert(any(SysMonitorAlert.class));
        verify(notifyService, never()).fire(any(), any());
    }

    @Test
    void silenceSuppressesResolveNotification() {
        SysMonitorAlertRule silenced = rule(1L, 85, "gt", 1);
        silenced.setSilenceUntil(LocalDateTime.now().plusMinutes(30));
        when(ruleMapper.selectList(any())).thenReturn(List.of(silenced));
        SysMonitorAlert open = new SysMonitorAlert();
        open.setAlertId(100L);
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(open);

        evaluator.onTick(tick(Map.of("system.cpu.usage", 20.0)));
        evaluator.onTick(tick(Map.of("system.cpu.usage", 21.0)));

        // 恢复照常落库,但静默期不发恢复通知(DD-16)
        ArgumentCaptor<SysMonitorAlert> captor = ArgumentCaptor.forClass(SysMonitorAlert.class);
        verify(alertMapper, times(1)).updateById(captor.capture());
        assertEquals("resolved", captor.getValue().getStatus());
        verify(notifyService, never()).resolve(any(), any());
    }

    @Test
    void missingSampleDoesNotAdvanceStreak() {
        // durationPoints=2:hit → 缺失 → hit;缺失点不推进计数,第 2 个有效点即开告警
        when(ruleMapper.selectList(any())).thenReturn(List.of(rule(1L, 85, "gt", 2)));
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(null);
        evaluator.onTick(tick(Map.of("system.cpu.usage", 90.0)));
        evaluator.onTick(tick(Map.of())); // 缺失样本
        verify(alertMapper, never()).insert(any(SysMonitorAlert.class));
        evaluator.onTick(tick(Map.of("system.cpu.usage", 91.0)));
        verify(alertMapper, times(1)).insert(any(SysMonitorAlert.class));

        // 缺失样本也不推进开放告警:open + 缺失 → 不刷新不恢复
        org.mockito.Mockito.doReturn(List.of(rule(1L, 85, "gt", 1)))
                .when(ruleMapper).selectList(any());
        SysMonitorAlert open = new SysMonitorAlert();
        open.setAlertId(100L);
        when(alertMapper.selectOpenByRuleId(1L)).thenReturn(open);
        evaluator.onTick(tick(Map.of()));
        verify(alertMapper, never()).updateById(any(SysMonitorAlert.class));
    }

    @Test
    void disabledGateSkipsEvaluation() {
        properties.setEnabled(false);
        evaluator.onTick(tick(Map.of("system.cpu.usage", 90.0)));
        verify(ruleMapper, never()).selectList(any());
    }

    @Test
    void evaluationFailureIsSwallowedForNextTick() {
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(ruleMapper).selectList(any());
        // 不抛出:整体异常吞掉,下一 tick 自愈(§2.4)
        evaluator.onTick(tick(Map.of("system.cpu.usage", 90.0)));
        org.mockito.Mockito.doReturn(List.of()).when(ruleMapper).selectList(any());
        evaluator.onTick(tick(Map.of("system.cpu.usage", 90.0)));
    }

    @Test
    void compareCoversAllComparators() {
        assertTrue(AlertEvaluator.compare(86, "gt", 85));
        assertFalse(AlertEvaluator.compare(85, "gt", 85));
        assertTrue(AlertEvaluator.compare(85, "gte", 85));
        assertTrue(AlertEvaluator.compare(84, "lt", 85));
        assertTrue(AlertEvaluator.compare(85, "lte", 85));
        assertTrue(AlertEvaluator.compare(85, "eq", 85));
        assertFalse(AlertEvaluator.compare(86, "eq", 85));
        // 未知比较符:恒 false,不触发
        assertFalse(AlertEvaluator.compare(86, "bogus", 85));
    }
}
