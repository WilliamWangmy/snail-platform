package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.diag.DiagCheck;
import com.snail.platform.function.monitor.metric.MetricKey;
import com.snail.platform.function.monitor.metric.MetricRingBuffer;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * http.error.rate:近 5 分钟 5xx 占比(按采样值的窗口均值,非单 tick 值);>1% warn、>5% fail;
 * 样本不足 pass(附说明)。
 */
@Component
public class HttpErrorRateCheck implements DiagCheck {

    private final MetricRingBuffer ringBuffer;

    public HttpErrorRateCheck(MetricRingBuffer ringBuffer) {
        this.ringBuffer = ringBuffer;
    }

    @Override
    public String key() {
        return "http.error.rate";
    }

    @Override
    public String name() {
        return "HTTP 5xx 占比";
    }

    @Override
    public Item run() {
        List<MetricRingBuffer.MetricPoint> points =
                ringBuffer.snapshot(java.util.Set.of(MetricKey.HTTP_SERVER_ERROR_RATE.key()))
                        .get(MetricKey.HTTP_SERVER_ERROR_RATE.key());
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        List<MetricRingBuffer.MetricPoint> window = points == null ? List.of()
                : points.stream().filter(p -> p.t() >= cutoff).toList();
        if (window.isEmpty()) {
            return Item.pass(key(), name(), null, "近 5 分钟无采样样本(应用未收到请求或采样未满两个 tick)", null);
        }
        double avg = window.stream().mapToDouble(MetricRingBuffer.MetricPoint::v).average().orElse(0);
        double rounded = Math.round(avg * 10) / 10.0;
        if (rounded > 5) {
            return Item.fail(key(), name(), null, "近 5 分钟 5xx 均值 " + rounded + "%(>5%)",
                    "在日志页按时间窗检索 ERROR 堆栈定位异常来源");
        }
        if (rounded > 1) {
            return Item.warn(key(), name(), null, "近 5 分钟 5xx 均值 " + rounded + "%(>1%)",
                    "关注错误趋势与最近的发布变更");
        }
        return Item.pass(key(), name(), null, "近 5 分钟 5xx 均值 " + rounded + "%", null);
    }
}
