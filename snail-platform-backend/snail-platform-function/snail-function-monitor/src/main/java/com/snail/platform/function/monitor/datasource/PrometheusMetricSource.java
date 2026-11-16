package com.snail.platform.function.monitor.datasource;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.MetricKey;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:Prometheus 指标源——按 queries 映射逐键查 /api/v1/query,虚拟并行总预算内收集(DD-06);
 * 空结果/非 success/超时的键缺席(采样器回退内置),baseUrl 为空 fetch 直接返回空。
 * 上游:MetricSampler(按 metrics.source 取用);下游:Prometheus HTTP API。
 * 口径:地址只来自 monitor.yml(SSRF 收口);凭据 basic auth 只进请求头不回吐;
 * 失败 log.warn 不抛出;Bean 常驻(name() 供采样器按配置取用)。
 */
@Component
public class PrometheusMetricSource implements MetricSource {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricSource.class);

    private final MonitorProperties properties;
    private final ObjectMapper objectMapper;

    /** 连接层复用(切 prometheus 源后每 tick ~23 键并发查询,HttpClient 每键新建会耗尽端口/句柄);
     *  connect 超时取配置值,请求级超时仍按 timeout-ms 每次设置 */
    private final java.net.http.HttpClient client;

    public PrometheusMetricSource(MonitorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getMetrics().getPrometheus().getTimeoutMs()))
                .build();
    }

    @Override
    public String name() {
        return "prometheus";
    }

    @Override
    public Map<String, Double> fetch(Set<String> keys) {
        Map<String, String> queries = properties.getMetrics().getPrometheus().getQueries();
        if (properties.getMetrics().getPrometheus().getBaseUrl() == null
                || properties.getMetrics().getPrometheus().getBaseUrl().isBlank()) {
            return Map.of();
        }
        // 请求键 ∩ 映射键:未映射的键回退内置(DD-06)
        List<String> mapped = keys.stream().filter(queries::containsKey).toList();
        if (mapped.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (String key : mapped) {
                futures.add(executor.submit(() -> {
                    Double value = queryOne(queries.get(key));
                    if (value != null) {
                        synchronized (result) {
                            result.put(key, value);
                        }
                    }
                    return null;
                }));
            }
            long deadline = System.currentTimeMillis() + properties.getMetrics().getPrometheus().getTotalTimeoutMs();
            for (Future<Void> future : futures) {
                try {
                    future.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    future.cancel(true);
                    log.warn("monitor prometheus 查询超时/失败: {}", e.getMessage());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return result;
    }

    /** 单键查询:GET /api/v1/query?query=...;空结果/非 success 返回 null */
    private Double queryOne(String promql) {
        try {
            String url = properties.getMetrics().getPrometheus().getBaseUrl()
                    + "/api/v1/query?query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8);
            java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getMetrics().getPrometheus().getTimeoutMs()))
                    .GET();
            String username = properties.getMetrics().getPrometheus().getUsername();
            String password = properties.getMetrics().getPrometheus().getPassword();
            if (username != null && !username.isBlank()) {
                String auth = java.util.Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                request.header("Authorization", "Basic " + auth);
            }
            java.net.http.HttpResponse<String> response =
                    client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                return null;
            }
            JsonNode first = root.path("data").path("result").path(0);
            if (first.isMissingNode() || first.path("value").path(1).isMissingNode()) {
                return null;
            }
            return Double.parseDouble(first.path("value").path(1).asText());
        } catch (Exception e) {
            log.warn("monitor prometheus 查询失败: {}", e.getMessage());
            return null;
        }
    }

    /** 映射键数(状态端点 mappedKeys 出参):queries ∩ MetricKey.all() */
    public int mappedKeyCount() {
        Set<String> all = MetricKey.all();
        return (int) properties.getMetrics().getPrometheus().getQueries().keySet().stream()
                .filter(all::contains).count();
    }

    /** 总键数(状态端点 totalKeys 出参) */
    public int totalKeyCount() {
        return MetricKey.all().size();
    }
}
