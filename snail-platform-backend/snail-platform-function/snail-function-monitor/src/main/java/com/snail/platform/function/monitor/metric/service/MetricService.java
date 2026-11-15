package com.snail.platform.function.monitor.metric.service;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.metric.MetricKey;
import com.snail.platform.function.monitor.metric.MetricRingBuffer;
import com.snail.platform.function.monitor.metric.MetricSampler;
import com.snail.platform.function.monitor.metric.dto.HistoryVO;
import com.snail.platform.function.monitor.metric.dto.MetricHistoryQuery;
import com.snail.platform.function.monitor.metric.dto.MetricSeriesVO;
import com.snail.platform.function.monitor.metric.dto.RealtimeVO;
import com.snail.platform.function.monitor.metric.mapper.SysMonitorMetricMapper;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 职责:指标查询服务——realtime 读环形缓冲(恒内存)、history 读采样表(恒本地 DB,不直连外部 query_range),
 * step 白名单与 2000 点保护在 service 首行(§4.5)。
 * 上游:MetricController;下游:MetricRingBuffer / SysMonitorMetricMapper。
 * 口径:realtime 只返回有数据的键;history 原始点 >2000 且 step<300 时 400 提示缩窗或调大粒度;
 * 跨度 ≤7 天;step ∈ {60,300,3600}(缺省 300)。
 */
@Service
public class MetricService {

    /** 2000 点保护阈值 */
    private static final long RAW_POINT_LIMIT = 2000;

    /** step 白名单 */
    private static final Set<Long> STEPS = Set.of(60L, 300L, 3600L);

    private final MetricRingBuffer ringBuffer;
    private final MetricSampler sampler;
    private final SysMonitorMetricMapper mapper;

    public MetricService(MetricRingBuffer ringBuffer, MetricSampler sampler, SysMonitorMetricMapper mapper) {
        this.ringBuffer = ringBuffer;
        this.sampler = sampler;
        this.mapper = mapper;
    }

    /** 实时曲线:环形缓冲拷贝,minutes 1-60 校验 */
    public RealtimeVO realtime(int minutes) {
        if (minutes < 1 || minutes > 60) {
            throw BizException.badRequest("minutes 取值 1-60");
        }
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        MetricSampler.TickSnapshot tick = sampler.lastTick();
        List<MetricSeriesVO> series = new ArrayList<>();
        ringBuffer.snapshot(MetricKey.all()).forEach((metric, points) -> {
            List<MetricSeriesVO.Point> filtered = points.stream()
                    .filter(p -> p.t() >= cutoff)
                    .map(p -> new MetricSeriesVO.Point(p.t(), p.v()))
                    .toList();
            if (!filtered.isEmpty()) {
                series.add(new MetricSeriesVO(metric, unitOf(metric), filtered));
            }
        });
        return new RealtimeVO(series, tick.metricsSource(), tick.degraded());
    }

    /** 历史曲线:DB 聚合;step 白名单、跨度、2000 点保护 */
    public HistoryVO history(MetricHistoryQuery query) {
        Optional<MetricKey> metricKey = MetricKey.fromKey(query.metric());
        if (metricKey.isEmpty()) {
            throw BizException.badRequest("指标键不在键表内");
        }
        long step = query.step() == null ? 300L : query.step();
        if (!STEPS.contains(step)) {
            throw BizException.badRequest("step 只支持 60/300/3600 秒");
        }
        if (query.beginTime().isAfter(query.endTime())) {
            throw BizException.badRequest("开始时间不能晚于结束时间");
        }
        if (ChronoUnit.HOURS.between(query.beginTime(), query.endTime()) > 24 * 7) {
            throw BizException.badRequest("时间跨度不能超过 7 天");
        }
        long rawCount = mapper.countByRange(query.metric(), query.beginTime(), query.endTime());
        if (rawCount > RAW_POINT_LIMIT && step < 300) {
            throw BizException.badRequest("时间窗内原始采样点超过 2000,请将粒度调至 300 秒以上或缩小时间窗");
        }
        List<SysMonitorMetricMapper.MetricBucketRow> rows =
                mapper.aggregateByStep(query.metric(), query.beginTime(), query.endTime(), step);
        List<MetricSeriesVO.Point> points = rows.stream()
                .filter(row -> row.bucket() != null && row.value() != null)
                .map(row -> new MetricSeriesVO.Point(row.bucket() * 1000, row.value()))
                .toList();
        return new HistoryVO(query.metric(), metricKey.get().unit(), step, points);
    }

    /** 告警详情 series(DD-10):按 metric + first~last 取原始点,最多 200 点(超出按 60s 抽样由调用方保证窗口) */
    public List<MetricSeriesVO.Point> rawPoints(String metric, LocalDateTime begin, LocalDateTime end, int maxPoints) {
        long rawCount = mapper.countByRange(metric, begin, end);
        long step = rawCount > maxPoints ? 60 : 1;
        return mapper.aggregateByStep(metric, begin, end, step).stream()
                .filter(row -> row.bucket() != null && row.value() != null)
                .limit(maxPoints)
                .map(row -> new MetricSeriesVO.Point(row.bucket() * 1000, row.value()))
                .toList();
    }

    private String unitOf(String metric) {
        return MetricKey.fromKey(metric).map(MetricKey::unit).orElse("");
    }
}
