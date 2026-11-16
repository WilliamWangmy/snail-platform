package com.snail.platform.function.monitor.metric.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.metric.MetricRingBuffer;
import com.snail.platform.function.monitor.metric.MetricSampler;
import com.snail.platform.function.monitor.metric.mapper.SysMonitorMetricMapper;
import com.snail.platform.function.monitor.metric.dto.HistoryVO;
import com.snail.platform.function.monitor.metric.dto.MetricHistoryQuery;
import com.snail.platform.function.monitor.metric.dto.RealtimeVO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 职责:指标查询服务纯单测——realtime minutes 校验、history step 白名单/跨度/2000 点保护(§4.5)。
 * 上游:MetricService;下游:Mockito 替身 ringBuffer/sampler/mapper,不连库。
 */
class MetricServiceTest {

    private final MetricRingBuffer ringBuffer = Mockito.mock(MetricRingBuffer.class);
    private final MetricSampler sampler = Mockito.mock(MetricSampler.class);
    private final SysMonitorMetricMapper mapper = Mockito.mock(SysMonitorMetricMapper.class);

    private final MetricService service = new MetricService(ringBuffer, sampler, mapper);

    @BeforeEach
    void stubTick() {
        lenient().when(sampler.lastTick())
                .thenReturn(new MetricSampler.TickSnapshot(0, "builtin", false));
    }

    @Test
    void realtimeRejectsMinutesOutOfRange() {
        assertThrows(BizException.class, () -> service.realtime(0));
        assertThrows(BizException.class, () -> service.realtime(61));
    }

    @Test
    void realtimeOnlyReturnsKeysWithPoints() {
        long now = System.currentTimeMillis();
        when(ringBuffer.snapshot(any())).thenReturn(Map.of(
                "system.cpu.usage", List.of(new MetricRingBuffer.MetricPoint(now - 60_000, 12.5)),
                "http.server.qps", List.of()));
        RealtimeVO vo = service.realtime(60);
        // 无点键不出现在 series(§4.5:只返回有数据的键)
        assertEquals(1, vo.series().size());
        assertEquals("system.cpu.usage", vo.series().get(0).metric());
        assertEquals(1, vo.series().get(0).points().size());
        assertEquals("builtin", vo.metricsSource());
    }

    @Test
    void historyRejectsUnknownMetricKey() {
        MetricHistoryQuery query = new MetricHistoryQuery(
                "not.a.key", LocalDateTime.now().minusHours(1), LocalDateTime.now(), 300L);
        assertThrows(BizException.class, () -> service.history(query));
    }

    @Test
    void historyRejectsStepOutsideWhitelist() {
        MetricHistoryQuery query = new MetricHistoryQuery(
                "system.cpu.usage", LocalDateTime.now().minusHours(1), LocalDateTime.now(), 90L);
        BizException e = assertThrows(BizException.class, () -> service.history(query));
        assertTrue(e.getMessage().contains("step"));
    }

    @Test
    void historyRejectsBeginAfterEnd() {
        MetricHistoryQuery query = new MetricHistoryQuery(
                "system.cpu.usage", LocalDateTime.now(), LocalDateTime.now().minusHours(1), 300L);
        assertThrows(BizException.class, () -> service.history(query));
    }

    @Test
    void historyRejectsSpanOverSevenDays() {
        MetricHistoryQuery query = new MetricHistoryQuery(
                "system.cpu.usage", LocalDateTime.now().minusHours(24 * 8), LocalDateTime.now(), 3600L);
        assertThrows(BizException.class, () -> service.history(query));
    }

    @Test
    void historyProtectionTriggersOnlyForSmallStep() {
        LocalDateTime begin = LocalDateTime.now().minusHours(24);
        LocalDateTime end = LocalDateTime.now();
        when(mapper.countByRange(eq("system.cpu.usage"), any(), any())).thenReturn(2001L);

        // step=60 且原始点 >2000 → 400(2000 点保护)
        MetricHistoryQuery smallStep = new MetricHistoryQuery("system.cpu.usage", begin, end, 60L);
        BizException e = assertThrows(BizException.class, () -> service.history(smallStep));
        assertTrue(e.getMessage().contains("2000"));

        // step=300 放行,即使原始点更多
        when(mapper.aggregateByStep(eq("system.cpu.usage"), any(), any(), eq(300L))).thenReturn(List.of(
                new SysMonitorMetricMapper.MetricBucketRow(1_700_000_000L, 42.5)));
        MetricHistoryQuery okStep = new MetricHistoryQuery("system.cpu.usage", begin, end, 300L);
        HistoryVO vo = service.history(okStep);
        assertEquals(300L, vo.step());
        assertEquals(1, vo.points().size());
        assertEquals(1_700_000_000_000L, vo.points().get(0).t());
        assertEquals(42.5, vo.points().get(0).v());
    }

    @Test
    void historyDefaultsStepTo300() {
        LocalDateTime begin = LocalDateTime.now().minusHours(1);
        MetricHistoryQuery query = new MetricHistoryQuery("system.cpu.usage", begin, LocalDateTime.now(), null);
        when(mapper.countByRange(anyString(), any(), any())).thenReturn(10L);
        when(mapper.aggregateByStep(anyString(), any(), any(), eq(300L))).thenReturn(List.of());
        HistoryVO vo = service.history(query);
        assertEquals(300L, vo.step());
    }

    @Test
    void rawPointsDownsamplesBeyondMaxPoints() {
        // 原始点 201 > maxPoints 200 → 抽样步长 60
        when(mapper.countByRange(anyString(), any(), any())).thenReturn(201L);
        when(mapper.aggregateByStep(anyString(), any(), any(), eq(60L))).thenReturn(List.of(
                new SysMonitorMetricMapper.MetricBucketRow(1L, 1.0),
                new SysMonitorMetricMapper.MetricBucketRow(2L, 2.0)));
        var points = service.rawPoints("system.cpu.usage", LocalDateTime.now().minusHours(1), LocalDateTime.now(), 200);
        assertEquals(2, points.size());
    }
}
