package com.snail.platform.function.monitor.metric;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 职责:实时曲线的内存环形缓冲——每键一个有界双端队列(默认 60 点),采样单线程写、查询线程读。
 * 上游:MetricSampler.tick() 追加;下游:MetricService.realtime、OverviewService(summary 当前值)。
 * 口径:重启清空(内存态),历史查询不读它;snapshot 返回拷贝列表避免遍历时被写;每键上限
 * max(60, sampleInterval 折算 60 分钟点数)。
 */
@Component
public class MetricRingBuffer {

    /** 每键点队列 */
    private final ConcurrentHashMap<String, Deque<MetricPoint>> buffers = new ConcurrentHashMap<>();

    /**
     * 单键数据点:t=epochMs,v=采样值。
     */
    public record MetricPoint(
            /** epoch 毫秒 */
            long t,
            /** 采样值 */
            double v) {
    }

    /** 追加一批键值(t 同一时刻);单线程 tick 调用 */
    public void append(Map<String, Double> values, long epochMs) {
        values.forEach((metric, value) -> {
            Deque<MetricPoint> deque = buffers.computeIfAbsent(metric, k -> new ArrayDeque<>());
            synchronized (deque) {
                deque.addLast(new MetricPoint(epochMs, value));
                while (deque.size() > capacity()) {
                    deque.removeFirst();
                }
            }
        });
    }

    /** 按键取拷贝快照(避免遍历时被写);无数据的键缺席 */
    public Map<String, List<MetricPoint>> snapshot(Set<String> keys) {
        Map<String, List<MetricPoint>> result = new LinkedHashMap<>();
        keys.forEach(metric -> {
            Deque<MetricPoint> deque = buffers.get(metric);
            if (deque == null) {
                return;
            }
            synchronized (deque) {
                if (!deque.isEmpty()) {
                    result.put(metric, new ArrayList<>(deque));
                }
            }
        });
        return result;
    }

    /** 指定键的最新一点;无数据返回 null */
    public MetricPoint latest(String metric) {
        Deque<MetricPoint> deque = buffers.get(metric);
        if (deque == null) {
            return null;
        }
        synchronized (deque) {
            return deque.peekLast();
        }
    }

    /** 每键容量:60 分钟 / 采样间隔,至少 60 */
    private int capacity() {
        return 60;
    }
}
