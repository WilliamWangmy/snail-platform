package com.snail.platform.function.monitor.diag.service;

import com.snail.platform.function.monitor.config.MonitorProperties;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 职责:环境快照服务——jvm/os/app/monitor 四组键值 + 敏感键掩码(DD-22)。
 * 上游:DiagController;下游:Environment、MXBean、MonitorProperties。
 * 口径:键名小写包含 secret/password/token/key/credential → "******"(宁多掩不漏);
 * username 不掩码;输出不含任何凭据明文(单测断言)。
 */
@Service
public class EnvSnapshotService {

    /** 敏感词表(键名小写 contains 即掩码) */
    private static final List<String> SENSITIVE_WORDS = List.of(
            "secret", "password", "token", "key", "credential");

    private final MonitorProperties properties;
    private final Environment environment;

    public EnvSnapshotService(MonitorProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /** 分组快照:jvm/os/app/monitor */
    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        result.put("jvm", jvmGroup());
        result.put("os", osGroup());
        result.put("app", appGroup());
        result.put("monitor", monitorGroup());
        // 全组统一掩码自检
        result.forEach((group, values) -> values.replaceAll((key, value) -> mask(key, value)));
        return result;
    }

    private Map<String, String> jvmGroup() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("java.version", System.getProperty("java.version"));
        values.put("java.vendor", System.getProperty("java.vendor"));
        values.put("jvm.startTime", java.time.Instant.ofEpochMilli(runtime.getStartTime()).toString());
        values.put("jvm.uptimeSeconds", String.valueOf(runtime.getUptime() / 1000));
        values.put("jvm.heapMaxMb", String.valueOf(
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / 1048576));
        values.put("jvm.processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        return values;
    }

    private Map<String, String> osGroup() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("os.name", System.getProperty("os.name"));
        values.put("os.version", System.getProperty("os.version"));
        values.put("os.arch", System.getProperty("os.arch"));
        return values;
    }

    private Map<String, String> appGroup() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("app.name", environment.getProperty("spring.application.name", "snail-platform"));
        values.put("app.profiles", String.join(",", environment.getActiveProfiles()));
        return values;
    }

    private Map<String, String> monitorGroup() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("monitor.enabled", String.valueOf(properties.isEnabled()));
        values.put("monitor.metrics.source", properties.getMetrics().getSource());
        values.put("monitor.logs.source", properties.getLogs().getSource());
        values.put("monitor.db.slowSql.enabled", String.valueOf(properties.getDb().getSlowSql().isEnabled()));
        values.put("monitor.db.server.enabled", String.valueOf(properties.getDb().getServer().isEnabled()));
        values.put("monitor.arthas.enabled", String.valueOf(properties.getArthas().isEnabled()));
        values.put("monitor.arthas.configured", String.valueOf(
                properties.getArthas().getBaseUrl() != null && !properties.getArthas().getBaseUrl().isBlank()));
        values.put("monitor.links.grafanaConfigured", String.valueOf(
                properties.getLinks().getGrafanaUrl() != null && !properties.getLinks().getGrafanaUrl().isBlank()));
        values.put("monitor.diag.agentConfigured", String.valueOf(
                properties.getDiag().getAgentHealthUrl() != null
                        && !properties.getDiag().getAgentHealthUrl().isBlank()));
        return values;
    }

    /** 掩码:键名小写包含敏感词 → ****** */
    static String mask(String key, String value) {
        String lower = key.toLowerCase();
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word)) {
                return "******";
            }
        }
        return value;
    }
}
