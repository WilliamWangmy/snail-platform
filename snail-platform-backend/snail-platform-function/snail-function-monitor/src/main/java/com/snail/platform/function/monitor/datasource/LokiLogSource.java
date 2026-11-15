package com.snail.platform.function.monitor.datasource;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.log.LogLineParser;
import com.snail.platform.function.monitor.log.LogLineParser.LogLine;
import com.snail.platform.function.monitor.log.dto.AppLogDetailQuery;
import com.snail.platform.function.monitor.log.dto.AppLogDetailVO;
import com.snail.platform.function.monitor.log.dto.AppLogPageQuery;
import com.snail.platform.function.monitor.log.dto.AppLogPageVO;
import com.snail.platform.function.monitor.log.dto.LogFilesVO;
import com.snail.platform.function.monitor.log.dto.TailQuery;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:Loki 日志源——query_range 拼 LogQL(selector + |= 行过滤),解析 values 行文本复用 LogLineParser;
 * page 方向 backward、tail 方向 forward;scannedLines 恒 0;不可达 400 不静默回退 file(§5.3)。
 * 上游:AppLogService(按 logs.source 取用);下游:Loki HTTP API。
 * 口径:detail 直接 400「当前日志源不支持行上下文」;keyword/traceId 转义双引号与反斜杠;
 * 行过滤是子串语义近似,级别无字样行可能漏检(已知口径,可回文件模式)。
 */
@Component
public class LokiLogSource implements LogSource {

    private final MonitorProperties properties;
    private final ObjectMapper objectMapper;

    public LokiLogSource(MonitorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "loki";
    }

    @Override
    public LogFilesVO files() {
        return new LogFilesVO("loki", List.of(
                new LogFilesVO.Item("loki", "loki: " + properties.getLogs().getLoki().getSelector(), 0, null)));
    }

    @Override
    public AppLogPageVO page(AppLogPageQuery query) {
        String logql = buildLogQl(query.level(), query.traceId(), query.keyword());
        long startNs = toNanos(query.beginTime());
        long endNs = toNanos(query.endTime());
        List<LogLine> lines = queryRange(logql, startNs, endNs, query.limit(), "backward");
        lines.sort(Comparator.comparing(LogLine::time).reversed().thenComparing(LogLine::lineNo,
                Comparator.reverseOrder()));
        boolean truncated = lines.size() >= query.limit();
        return new AppLogPageVO("loki", lines.stream().map(com.snail.platform.function.monitor.log.dto.LogLineVO::from)
                .toList(), 0, truncated);
    }

    @Override
    public List<LogLine> tail(TailQuery query) {
        String logql = buildLogQl(null, null, null);
        long startNs = query.since() * 1_000_000;
        long endNs = toNanos(LocalDateTime.now());
        return queryRange(logql, startNs, endNs, query.limit(), "forward");
    }

    @Override
    public AppLogDetailVO detail(AppLogDetailQuery query) {
        throw BizException.badRequest("当前日志源不支持行上下文");
    }

    // ---------- 内部 ----------

    /** LogQL:selector + |= "level" + |= "traceId" + |= "keyword"(分别可选) */
    private String buildLogQl(String level, String traceId, String keyword) {
        StringBuilder sb = new StringBuilder(properties.getLogs().getLoki().getSelector());
        if (level != null && !level.isBlank()) {
            sb.append(" |= ").append(quote(level));
        }
        if (traceId != null && !traceId.isBlank()) {
            sb.append(" |= ").append(quote(traceId));
        }
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" |= ").append(quote(keyword));
        }
        return sb.toString();
    }

    /** LogQL 字符串字面量:转义反斜杠与双引号 */
    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** query_range 并解析 data.result[].values[];不可达 400 不回退 */
    private List<LogLine> queryRange(String logql, long startNs, long endNs, int limit, String direction) {
        String url = properties.getLogs().getLoki().getBaseUrl()
                + "/loki/api/v1/query_range?query=" + URLEncoder.encode(logql, StandardCharsets.UTF_8)
                + "&start=" + startNs + "&end=" + endNs
                + "&limit=" + limit + "&direction=" + direction;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getLogs().getLoki().getTimeoutMs()))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofMillis(properties.getLogs().getLoki().getTimeoutMs())).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                throw BizException.badRequest("日志源不可用: " + root.path("error").asText("未知错误"));
            }
            List<LogLine> lines = new ArrayList<>();
            long lineNo = 0;
            for (JsonNode stream : root.path("data").path("result")) {
                for (JsonNode pair : stream.path("values")) {
                    if (pair.size() < 2) {
                        continue;
                    }
                    long tsNanos = pair.get(0).asLong();
                    String text = pair.get(1).asText();
                    LogLine parsed = LogLineParser.parse(text);
                    LocalDateTime time = Instant.ofEpochSecond(tsNanos / 1_000_000_000L,
                            tsNanos % 1_000_000_000L).atZone(ZoneId.systemDefault()).toLocalDateTime();
                    if (parsed != null) {
                        lineNo++;
                        lines.add(new LogLine("loki", lineNo, time, parsed.level(), parsed.traceId(),
                                parsed.thread(), parsed.logger(), parsed.message(), text));
                    } else {
                        // 堆栈行并入上一条
                        if (!lines.isEmpty()) {
                            LogLine previous = lines.get(lines.size() - 1);
                            lines.set(lines.size() - 1, new LogLine(previous.fileId(), previous.lineNo(),
                                    previous.time(), previous.level(), previous.traceId(), previous.thread(),
                                    previous.logger(), LogLineParser.appendStack(previous.message(), text),
                                    previous.raw() + "\n" + text));
                        }
                    }
                }
            }
            return lines;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw BizException.badRequest("日志源不可用: " + e.getMessage());
        }
    }

    private long toNanos(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() * 1_000_000;
    }
}
