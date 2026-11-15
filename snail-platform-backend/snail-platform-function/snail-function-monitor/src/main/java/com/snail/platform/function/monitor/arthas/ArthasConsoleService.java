package com.snail.platform.function.monitor.arthas;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.metric.MetricTickEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:Arthas tunnel 状态服务——GET {base}/actuator/arthas(basic auth)解析 agents/clientConnections,
 * 快照由采样 tick 驱动刷新(30s 缓存口径,§5.8)。
 * 上游:DataSourceStatusService(同 tick 顺序消费)、ArthasStatusController;下游:tunnel actuator。
 * 口径:enabled=false 返回空态结构;401/超时/非 JSON → reachable=false + lastError(截 200 字);
 * agents 最多 50 条;consoleBaseUrl 只回 scheme+host+port(去 path/query/userinfo);
 * 凭据永不回吐;不做命令代理/会话托管/命令审计。
 */
@Service
public class ArthasConsoleService {

    private static final Logger log = LoggerFactory.getLogger(ArthasConsoleService.class);

    private static final int MAX_AGENTS = 50;

    private final MonitorProperties properties;
    private final ObjectMapper objectMapper;

    /** 最近快照(volatile,tick 刷新) */
    private volatile ArthasStatusVO snapshot = null;

    /** 单 agent 行 */
    public record AgentInfo(
            /** agentId(agent 注册时写入) */
            String agentId,
            /** agent 主机 */
            String host,
            /** agent 端口 */
            Integer port,
            /** arthas 版本 */
            String arthasVersion) {
    }

    public ArthasConsoleService(MonitorProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 状态快照出参(§5.8 契约)。
     */
    public record ArthasStatusVO(
            boolean enabled,
            boolean reachable,
            Long latencyMs,
            String lastError,
            String tunnelVersion,
            String consoleBaseUrl,
            Integer wsPort,
            List<AgentInfo> agents,
            int agentCount,
            int clientConnectionCount,
            LocalDateTime checkedAt) {
    }

    /** 只读快照;未刷新过时返回首 probe 结果或空态 */
    public ArthasStatusVO status() {
        ArthasStatusVO current = snapshot;
        if (current != null) {
            return current;
        }
        return disabledOrEmpty();
    }

    /** 采样 tick 刷新(@Order(25):数据源状态之后) */
    @EventListener(MetricTickEvent.class)
    @Order(25)
    public void onTick(MetricTickEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            snapshot = probe();
        } catch (Exception e) {
            log.warn("monitor arthas 探测失败: {}", e.getMessage());
        }
    }

    /** 执行一次探测并构造快照;enabled=false 直接空态 */
    public ArthasStatusVO probe() {
        if (!properties.getArthas().isEnabled()) {
            return disabledOrEmpty();
        }
        String base = properties.getArthas().getBaseUrl();
        if (base == null || base.isBlank()) {
            return new ArthasStatusVO(true, false, null, "未配置 tunnel 地址", null, null,
                    properties.getArthas().getWsPort(), List.of(), 0, 0, LocalDateTime.now().withNano(0));
        }
        long start = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getArthas().getTimeoutMs())).build();
            String auth = Base64.getEncoder().encodeToString((properties.getArthas().getUsername() + ":"
                    + properties.getArthas().getPassword()).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                            .uri(URI.create(stripTrailingSlash(base) + "/actuator/arthas"))
                            .timeout(Duration.ofMillis(properties.getArthas().getTimeoutMs()))
                            .header("Authorization", "Basic " + auth)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            if (response.statusCode() == 401) {
                return failState(latencyMs, "认证失败(401),检查 actuator basic auth");
            }
            if (response.statusCode() != 200) {
                return failState(latencyMs, "HTTP " + response.statusCode());
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(response.body());
            } catch (Exception e) {
                return failState(latencyMs, "响应非 JSON");
            }
            List<AgentInfo> agents = new ArrayList<>();
            JsonNode agentsNode = root.path("agents");
            if (agentsNode.isObject()) {
                for (Map.Entry<String, JsonNode> entry : agentsNode.properties()) {
                    if (agents.size() >= MAX_AGENTS) {
                        break;
                    }
                    JsonNode agentNode = entry.getValue();
                    agents.add(new AgentInfo(entry.getKey(),
                            agentNode.path("host").asText(null),
                            agentNode.has("port") ? agentNode.path("port").asInt() : null,
                            agentNode.path("arthasVersion").asText(null)));
                }
            }
            int clientConnections = root.path("clientConnections").isObject()
                    ? root.path("clientConnections").size() : 0;
            return new ArthasStatusVO(true, true, latencyMs, null,
                    root.path("version").asText(null), originOf(base),
                    properties.getArthas().getWsPort(), agents, agents.size(), clientConnections,
                    LocalDateTime.now().withNano(0));
        } catch (Exception e) {
            return failState(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ArthasStatusVO disabledOrEmpty() {
        boolean enabled = properties.getArthas().isEnabled();
        return new ArthasStatusVO(enabled, false, null, null, null, null,
                enabled ? properties.getArthas().getWsPort() : null,
                List.of(), 0, 0, LocalDateTime.now().withNano(0));
    }

    private ArthasStatusVO failState(Long latencyMs, String error) {
        return new ArthasStatusVO(true, false, latencyMs, truncate(error), null, originOf(
                properties.getArthas().getBaseUrl()),
                properties.getArthas().getWsPort(), List.of(), 0, 0, LocalDateTime.now().withNano(0));
    }

    /** 只回 scheme+host+port(去 path/query/userinfo) */
    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getPort() > 0) {
                return uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
            }
            return uri.getScheme() + "://" + uri.getHost();
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

    /** 供数据源状态复用的最小探测结果 */
    public Map<String, Object> minimalStatus() {
        ArthasStatusVO status = status();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", status.enabled());
        result.put("reachable", status.reachable());
        result.put("agentCount", status.agentCount());
        result.put("baseUrl", status.consoleBaseUrl());
        return result;
    }
}
