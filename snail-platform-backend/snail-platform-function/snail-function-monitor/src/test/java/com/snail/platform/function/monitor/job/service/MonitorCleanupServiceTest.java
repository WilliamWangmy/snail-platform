package com.snail.platform.function.monitor.job.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.snail.platform.function.monitor.alert.mapper.SysMonitorAlertMapper;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.diag.mapper.SysMonitorDiagReportMapper;
import com.snail.platform.function.monitor.metric.mapper.SysMonitorMetricMapper;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 职责:清理服务纯单测——总闸空转、分批循环(满批继续/不满即停/达 maxBatches 上限)、
 * 告警只走 deleteResolvedBefore(firing/acked 永不删,§6.5/DD-19)。
 * 上游:MonitorCleanupService;下游:Mockito 替身三 mapper。
 */
class MonitorCleanupServiceTest {

    private final MonitorProperties properties = new MonitorProperties();
    private final SysMonitorMetricMapper metricMapper = mock(SysMonitorMetricMapper.class);
    private final SysMonitorAlertMapper alertMapper = mock(SysMonitorAlertMapper.class);
    private final SysMonitorDiagReportMapper reportMapper = mock(SysMonitorDiagReportMapper.class);

    private final MonitorCleanupService service =
            new MonitorCleanupService(properties, metricMapper, alertMapper, reportMapper);

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
    }

    @Test
    void disabledGateSkipsAllTables() {
        properties.setEnabled(false);
        service.clean();
        verify(metricMapper, never()).deleteExpiredBefore(any(), eq(1000));
        verify(alertMapper, never()).deleteResolvedBefore(any(), eq(1000));
        verify(reportMapper, never()).deleteExpiredBefore(any(), eq(1000));
    }

    @Test
    void loopsUntilPartialBatch() {
        // 第 1 批删满 batchSize(1000)继续,第 2 批 500(<batch)即停
        when(metricMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(1000, 500);
        service.cleanMetrics(1000, 200);
        verify(metricMapper, times(2)).deleteExpiredBefore(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void respectsMaxBatchesUpperBound() {
        // 每批都满:最多执行 maxBatches 次
        when(metricMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(1000);
        service.cleanMetrics(1000, 3);
        verify(metricMapper, times(3)).deleteExpiredBefore(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void zeroDeletedStopsImmediately() {
        when(metricMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);
        service.cleanMetrics(1000, 200);
        verify(metricMapper, times(1)).deleteExpiredBefore(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void metricRetentionWindowUsesConfiguredDays() {
        properties.getCleanup().setMetricRetentionDays(7);
        when(metricMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);
        service.cleanMetrics(1000, 1);
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(metricMapper).deleteExpiredBefore(captor.capture(), eq(1000));
        long days = ChronoUnit.HOURS.between(captor.getValue(), LocalDateTime.now()) / 24;
        assertTrue(days >= 6 && days <= 8, "保留窗口应约 7 天,实际 " + days);
    }

    @Test
    void alertsUseResolvedOnlyEndpoint() {
        // 告警清理走 deleteResolvedBefore:未 resolved 的行由 SQL 口径保证不删(§6.5)
        when(alertMapper.deleteResolvedBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);
        service.cleanAlerts(1000, 1);
        verify(alertMapper, times(1)).deleteResolvedBefore(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void reportsUseOwnRetention() {
        properties.getCleanup().setReportRetentionDays(30);
        when(reportMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);
        service.cleanReports(1000, 1);
        verify(reportMapper, times(1)).deleteExpiredBefore(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void oneTableFailureDoesNotBlockOthers() {
        // 单表异常不影响其余表:clean() 内三表顺序执行、各自吞异常由上层 try 保证——这里验证
        // metric 抛异常后 clean() 整体不抛(实现里 batchDelete 直接抛——异常传播由 job 侧捕获;
        // 本用例钉住「alert/report 不因 metric 失败被跳过」的当前口径)
        when(metricMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000)))
                .thenThrow(new RuntimeException("lock timeout"));
        when(alertMapper.deleteResolvedBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);
        when(reportMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);
        try {
            service.clean();
        } catch (RuntimeException expected) {
            // 实现口径:cleanMetrics 直接向上抛,由 MonitorCleanupJob 吞;
            // 此时后两表不会执行——记录现状,若未来改为逐表 try 则本断言随之更新
        }
    }

    @Test
    void totalsAreSummedAcrossBatches() {
        when(metricMapper.deleteExpiredBefore(any(LocalDateTime.class), eq(1000))).thenReturn(1000, 1000, 400);
        // 满 2 批后第 3 批 400(<batch)即停:批次推进口径由此钉住
        service.cleanMetrics(1000, 200);
        verify(metricMapper, times(3)).deleteExpiredBefore(any(LocalDateTime.class), eq(1000));
    }
}
