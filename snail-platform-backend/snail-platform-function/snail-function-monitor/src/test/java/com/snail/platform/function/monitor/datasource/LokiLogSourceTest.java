package com.snail.platform.function.monitor.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.log.dto.AppLogPageQuery;
import com.snail.platform.function.monitor.log.dto.AppLogPageVO;
import com.snail.platform.function.monitor.log.dto.LogFilesVO;
import com.snail.platform.function.monitor.log.dto.TailQuery;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 职责:Loki 日志源桩测试——LogQL 拼装(selector + |= 过滤与转义)、行解析复用 LogLineParser、
 * 堆栈行并入上一条、truncated 口径、detail 400、不可达 400 不回退(§9.2/§9.5)。
 * 上游:LokiLogSource;下游:内嵌 HttpServer 桩,不连真实 Loki。
 */
class LokiLogSourceTest {

    private HttpServer server;
    private final MonitorProperties properties = new MonitorProperties();
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");

    private LokiLogSource source;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/query_range", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            lastQuery.set(URLDecoder.decode(rawQuery, StandardCharsets.UTF_8));
            String line1 = "2026-11-16 10:00:00.001 ERROR [t1] [main] a.b.C - first";
            String stack = "\tat a.b.C:42";
            // 用 Jackson 序列化构造响应,避免手拼 JSON 转义出错;堆栈行(带制表符)由 LokiLogSource 并入上一条
            java.util.Map<String, Object> root = java.util.Map.of(
                    "status", "success",
                    "data", java.util.Map.of("result", java.util.List.of(java.util.Map.of(
                            "stream", java.util.Map.of("job", "snail-platform"),
                            "values", java.util.List.of(
                                    java.util.List.of("1760000000000000000", line1),
                                    java.util.List.of("1760000000000000001", stack))))));
            byte[] body = new tools.jackson.databind.ObjectMapper().writeValueAsBytes(root);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties.getLogs().getLoki().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getLogs().getLoki().setSelector("{job=\"snail-platform\"}");
        source = new LokiLogSource(properties, new tools.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private AppLogPageQuery pageQuery(String level, String traceId, String keyword, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return new AppLogPageQuery(now.minusHours(1), now, level, traceId, keyword, limit);
    }

    @Test
    void filesExposeSingleSelectorEntry() {
        LogFilesVO vo = source.files();
        assertEquals("loki", vo.source());
        assertEquals(1, vo.files().size());
        assertTrue(vo.files().get(0).name().contains("job=\"snail-platform\""));
    }

    @Test
    void pageBuildsLogQlWithFiltersAndEscapesQuotes() {
        AppLogPageVO vo = source.page(pageQuery("ERROR", "t1", "pay\"ment", 200));
        String q = lastQuery.get();
        // selector + |= 过滤顺序拼装(§9.2)
        assertTrue(q.contains("query={job=\"snail-platform\"} |= \"ERROR\" |= \"t1\" |= \"pay\\\"ment\""));
        // direction=backward、scannedLines 恒 0(§9.2)
        assertTrue(q.contains("direction=backward"));
        assertEquals(0, vo.scannedLines());
        assertEquals("loki", vo.source());
        // 一条结构化行 + 一条堆栈行并入 message(复用 LogLineParser)
        assertEquals(1, vo.rows().size());
        assertTrue(vo.rows().get(0).message().contains("first"));
        assertTrue(vo.rows().get(0).message().contains("at a.b.C:42"));
        assertEquals("ERROR", vo.rows().get(0).level());
    }

    @Test
    void truncatedWhenRowsReachLimit() {
        // 桩返回 1 行,limit=1 → rows.size() >= limit → truncated
        AppLogPageVO vo = source.page(pageQuery(null, null, null, 1));
        assertTrue(vo.truncated());
        // limit=200 → 不截断
        AppLogPageVO full = source.page(pageQuery(null, null, null, 200));
        assertTrue(!full.truncated());
    }

    @Test
    void tailUsesForwardDirection() {
        var lines = source.tail(new TailQuery(System.currentTimeMillis() - 60_000, 100));
        assertTrue(lastQuery.get().contains("direction=forward"));
        assertEquals(1, lines.size());
    }

    @Test
    void detailAlwaysRejectedWithContextHint() {
        BizException e = assertThrows(BizException.class,
                () -> source.detail(new com.snail.platform.function.monitor.log.dto.AppLogDetailQuery("loki", 1L, 50)));
        assertTrue(e.getMessage().contains("当前日志源不支持行上下文"));
    }

    @Test
    void unreachableLokiYields400NotSilentFallback() {
        // 关闭桩端口模拟不可达:400「日志源不可用」,不静默回退 file(§5.3)
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        properties.getLogs().getLoki().setBaseUrl("http://127.0.0.1:" + port);
        BizException e = assertThrows(BizException.class, () -> source.page(pageQuery(null, null, null, 10)));
        assertTrue(e.getMessage().contains("日志源不可用"));
    }

    @Test
    void nonSuccessStatusYields400WithLokiError() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/query_range", exchange -> {
            byte[] body = "{\"status\":\"error\",\"error\":\"parse error\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties.getLogs().getLoki().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        BizException e = assertThrows(BizException.class, () -> source.page(pageQuery(null, null, null, 10)));
        assertTrue(e.getMessage().contains("parse error"));
    }
}
