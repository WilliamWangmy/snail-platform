package com.snail.platform.function.monitor.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 职责:Prometheus 指标源桩测试——映射键取外部值、未映射键缺席回退、非 success/HTTP 500 缺席、
 * baseUrl 空直接空 Map(§9.1/§9.5)。
 * 上游:PrometheusMetricSource;下游:内嵌 HttpServer 桩,不连真实 Prometheus。
 */
class PrometheusMetricSourceTest {

    private HttpServer server;
    private final MonitorProperties properties = new MonitorProperties();

    /** 各路径最近一次收到的 Authorization 头(凭据不回吐,只验存在性) */
    private final Map<String, String> authHeaders = new ConcurrentHashMap<>();

    private PrometheusMetricSource source;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // /api/v1/query:按 query 参数返回固定体;成功体为 status=success + data.result[0].value[1]
        server.createContext("/api/v1/query", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            authHeaders.put(query, exchange.getRequestHeaders().getFirst("Authorization"));
            boolean ok = query.contains("up");
            byte[] body = (ok
                    ? "{\"status\":\"success\",\"data\":{\"result\":[{\"metric\":{},\"value\":[1760000000,\"42.5\"]}]}}"
                    : "{\"status\":\"error\",\"error\":\"bad query\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties.getMetrics().getPrometheus().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMetrics().getPrometheus().setQueries(Map.of(
                "system.cpu.usage", "up",
                "jvm.heap.used.percent", "nonexistent_query_marker"));
        properties.getMetrics().getPrometheus().setUsername("prom");
        properties.getMetrics().getPrometheus().setPassword("secret");
        source = new PrometheusMetricSource(properties, new tools.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void mappedKeysFetchExternalValuesAndUnmappedStayAbsent() {
        Map<String, Double> result = source.fetch(Set.of("system.cpu.usage", "jvm.heap.used.percent", "online.users"));
        // 映射键取到桩值
        assertEquals(42.5, result.get("system.cpu.usage"));
        // 非 success 的映射键缺席
        assertFalse(result.containsKey("jvm.heap.used.percent"));
        // 未映射键缺席(回退内置,DD-06)
        assertFalse(result.containsKey("online.users"));
    }

    @Test
    void basicAuthIsSentButNeverEchoed() {
        source.fetch(Set.of("system.cpu.usage"));
        String auth = authHeaders.values().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        assertTrue(auth != null && auth.startsWith("Basic "));
        // 凭据不回吐:配置对象口径由 DTO 保证,此处仅确认请求头携带
        assertFalse(String.valueOf(authHeaders).contains("prom:secret"));
    }

    @Test
    void blankBaseUrlReturnsEmptyWithoutCalling() {
        properties.getMetrics().getPrometheus().setBaseUrl("");
        Map<String, Double> result = source.fetch(Set.of("system.cpu.usage"));
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyIntersectionReturnsEmpty() {
        assertTrue(source.fetch(Set.of("online.users")).isEmpty());
    }

    @Test
    void httpErrorYieldsAbsentKey() throws IOException {
        // 500 响应:键缺席不抛出
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        properties.getMetrics().getPrometheus().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        PrometheusMetricSource broken = new PrometheusMetricSource(properties, new tools.jackson.databind.ObjectMapper());
        assertTrue(broken.fetch(Set.of("system.cpu.usage")).isEmpty());
    }

    @Test
    void mappedKeyCountIntersectsMetricKeyTable() {
        // 映射表 2 键 ∩ MetricKey.all():system.cpu.usage 在键表内,jvm.heap.used.percent 也在
        assertEquals(2, source.mappedKeyCount());
        assertTrue(source.totalKeyCount() >= 25);
    }
}
