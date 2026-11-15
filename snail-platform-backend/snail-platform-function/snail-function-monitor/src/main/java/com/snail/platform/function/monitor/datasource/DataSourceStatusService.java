package com.snail.platform.function.monitor.datasource;

import com.snail.platform.function.monitor.arthas.ArthasConsoleService;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.MetricTickEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * 职责:数据源状态服务——metrics/logs/arthas 三段可达性快照,tick 驱动刷新(30s 缓存口径,§5.7)。
 * 上游:MetricTickEvent(@Order(20),评估之后);下游:DataSourceController(只读快照)。
 * 口径:active 是实际生效态(builtin/prometheus/file/unavailable);reachable 用轻量探测
 * (Prometheus buildinfo/Loki ready/Arthas 复用控制台快照),2s 超时;凭据不回吐;
 * baseUrl 只回 scheme+host+port;首 tick 前返回 active=configured、reachable=false「等待首次探测」。
 */
@Service
public class DataSourceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceStatusService.class);

    private final MonitorProperties properties;
    private final PrometheusMetricSource prometheusMetricSource;
    private final ArthasConsoleService arthasConsoleService;

    /** 最近快照(volatile) */
    private volatile DataSourceStatusVO snapshot = null;

    public DataSourceStatusService(MonitorProperties properties,
                                   PrometheusMetricSource prometheusMetricSource,
                                   ArthasConsoleService arthasConsoleService) {
        this.properties = properties;
        this.prometheusMetricSource = prometheusMetricSource;
        this.arthasConsoleService = arthasConsoleService;
    }

    /**
     * 状态快照出参(§5.7 契约)。
     */
    public record DataSourceStatusVO(
            MetricsStatus metrics,
            LogsStatus logs,
            ArthasSummary arthas,
            Links links,
            LocalDateTime checkedAt) {

        public record MetricsStatus(
                String configured, String active, boolean reachable, Long latencyMs,
                String lastError, int mappedKeys, int totalKeys, String baseUrl) {
        }

        public record LogsStatus(
                String configured, String active, boolean reachable, Long latencyMs,
                String lastError, String selector, String baseUrl) {
        }

        public record ArthasSummary(
                boolean enabled, boolean reachable, int agentCount, String baseUrl) {
        }

        public record Links(
                String grafanaUrl) {
        }
    }

    /** 只读快照;首 tick 前返回「等待首次探测」结构 */
    public DataSourceStatusVO status() {
        DataSourceStatusVO current = snapshot;
        if (current != null) {
            return current;
        }
        return waitingSnapshot();
    }

    /** tick 驱动刷新(@Order(20):告警评估之后) */
    @EventListener(MetricTickEvent.class)
    @Order(20)
    public void onTick(MetricTickEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            snapshot = new DataSourceStatusVO(
                    metricsStatus(), logsStatus(), arthasSummary(),
                    new DataSourceStatusVO.Links(blankToNull(properties.getLinks().getGrafanaUrl())),
                    LocalDateTime.now().withNano(0));
        } catch (Exception e) {
            log.warn("monitor 数据源状态刷新失败: {}", e.getMessage());
        }
    }

    // ---------- 三段探测 ----------

    private DataSourceStatusVO.MetricsStatus metricsStatus() {
        String configured = properties.getMetrics().getSource();
        String baseUrl = properties.getMetrics().getPrometheus().getBaseUrl();
        if (!"prometheus".equals(configured) || baseUrl == null || baseUrl.isBlank()) {
            return new DataSourceStatusVO.MetricsStatus(configured, "builtin", true, null, null,
                    0, prometheusMetricSource.totalKeyCount(), null);
        }
        long start = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(stripTrailingSlash(baseUrl) + "/api/v1/status/buildinfo"))
                    .timeout(Duration.ofMillis(2000)).GET();
            String username = properties.getMetrics().getPrometheus().getUsername();
            if (username != null && !username.isBlank()) {
                String auth = Base64.getEncoder().encodeToString((username + ":"
                        + properties.getMetrics().getPrometheus().getPassword()).getBytes(StandardCharsets.UTF_8));
                request.header("Authorization", "Basic " + auth);
            }
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            boolean reachable = response.statusCode() == 200;
            return new DataSourceStatusVO.MetricsStatus("prometheus", reachable ? "prometheus" : "builtin",
                    reachable, latencyMs, reachable ? null : "HTTP " + response.statusCode(),
                    prometheusMetricSource.mappedKeyCount(), prometheusMetricSource.totalKeyCount(),
                    originOf(baseUrl));
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new DataSourceStatusVO.MetricsStatus("prometheus", "builtin", false, latencyMs,
                    truncate(e.getMessage()), prometheusMetricSource.mappedKeyCount(),
                    prometheusMetricSource.totalKeyCount(), originOf(baseUrl));
        }
    }

    private DataSourceStatusVO.LogsStatus logsStatus() {
        String configured = properties.getLogs().getSource();
        String baseUrl = properties.getLogs().getLoki().getBaseUrl();
        if (!"loki".equals(configured) || baseUrl == null || baseUrl.isBlank()) {
            return new DataSourceStatusVO.LogsStatus(configured, "file", true, null, null,
                    properties.getLogs().getLoki().getSelector(), null);
        }
        long start = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
            HttpResponse<Void> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(stripTrailingSlash(baseUrl) + "/ready"))
                            .timeout(Duration.ofMillis(2000)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            boolean reachable = response.statusCode() == 200;
            return new DataSourceStatusVO.LogsStatus("loki", reachable ? "loki" : "unavailable",
                    reachable, latencyMs, reachable ? null : "HTTP " + response.statusCode(),
                    properties.getLogs().getLoki().getSelector(), originOf(baseUrl));
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            return new DataSourceStatusVO.LogsStatus("loki", "unavailable", false, latencyMs,
                    truncate(e.getMessage()), properties.getLogs().getLoki().getSelector(), originOf(baseUrl));
        }
    }

    private DataSourceStatusVO.ArthasSummary arthasSummary() {
        ArthasConsoleService.ArthasStatusVO status = arthasConsoleService.status();
        return new DataSourceStatusVO.ArthasSummary(status.enabled(), status.reachable(),
                status.agentCount(), status.consoleBaseUrl());
    }

    // ---------- 工具 ----------

    private DataSourceStatusVO waitingSnapshot() {
        return new DataSourceStatusVO(
                new DataSourceStatusVO.MetricsStatus(properties.getMetrics().getSource(),
                        properties.getMetrics().getSource(), false, null, "等待首次探测", 0,
                        prometheusMetricSource.totalKeyCount(),
                        originOrNull(properties.getMetrics().getPrometheus().getBaseUrl())),
                new DataSourceStatusVO.LogsStatus(properties.getLogs().getSource(),
                        properties.getLogs().getSource(), false, null, "等待首次探测",
                        properties.getLogs().getLoki().getSelector(),
                        originOrNull(properties.getLogs().getLoki().getBaseUrl())),
                new DataSourceStatusVO.ArthasSummary(properties.getArthas().isEnabled(), false, 0,
                        originOrNull(properties.getArthas().getBaseUrl())),
                new DataSourceStatusVO.Links(blankToNull(properties.getLinks().getGrafanaUrl())),
                null);
    }

    private String originOrNull(String url) {
        return url == null || url.isBlank() ? null : originOf(url);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 只回 scheme+host+port */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getPort() > 0
                    ? uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort()
                    : uri.getScheme() + "://" + uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
