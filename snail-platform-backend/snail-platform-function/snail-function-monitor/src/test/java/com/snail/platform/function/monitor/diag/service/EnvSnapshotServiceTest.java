package com.snail.platform.function.monitor.diag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.function.monitor.config.MonitorProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 职责:环境快照纯单测——四组输出齐全、敏感键掩码 DD-22(contains 宁多掩不漏)、username 不掩、
 * 输出无凭据明文(§8.6 验收断言)。
 * 上游:EnvSnapshotService;下游:真实 MonitorProperties + MockEnvironment,不连库。
 */
class EnvSnapshotServiceTest {

    private final MonitorProperties properties = new MonitorProperties();
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.application.name", "snail-platform");

    private final EnvSnapshotService service = new EnvSnapshotService(properties, environment);

    @Test
    void snapshotHasFourGroups() {
        Map<String, Map<String, String>> snapshot = service.snapshot();
        assertEquals(4, snapshot.size());
        snapshot.forEach((group, values) -> {
            assertNotNull(values);
            assertFalse(values.isEmpty(), group + " 组不应为空");
        });
    }

    @Test
    void jvmGroupCarriesRuntimeFacts() {
        Map<String, String> jvm = service.snapshot().get("jvm");
        assertNotNull(jvm.get("java.version"));
        assertNotNull(jvm.get("jvm.startTime"));
        assertTrue(jvm.containsKey("jvm.processors"));
    }

    @Test
    void appGroupReflectsEnvironment() {
        Map<String, String> app = service.snapshot().get("app");
        assertEquals("snail-platform", app.get("app.name"));
    }

    @Test
    void monitorGroupExposesSwitchesButNotCredentials() {
        Map<String, String> monitor = service.snapshot().get("monitor");
        assertEquals("builtin", monitor.get("monitor.metrics.source"));
        // 配置的凭据字段绝不出现:只暴露 enabled/configured 布尔,不回吐 baseUrl/凭据
        monitor.keySet().forEach(key ->
                assertFalse(key.contains("baseUrl"), "monitor 组不得暴露地址字段:" + key));
        String all = String.valueOf(monitor.values());
        assertFalse(all.contains("http://"), "monitor 组不得回吐地址:" + all);
    }

    @Test
    void maskCoversSensitiveWordsCaseInsensitive() {
        assertEquals("******", EnvSnapshotService.mask("db.password", "p@ss"));
        assertEquals("******", EnvSnapshotService.mask("PASSword", "p"));
        assertEquals("******", EnvSnapshotService.mask("api-token", "t"));
        assertEquals("******", EnvSnapshotService.mask("secretKey", "s"));
        assertEquals("******", EnvSnapshotService.mask("my.credentials", "c"));
        // 宁多掩不漏(DD-22):monkey 含 "key" 也掩
        assertEquals("******", EnvSnapshotService.mask("monkey", "banana"));
        // username 不掩码
        assertEquals("admin", EnvSnapshotService.mask("username", "admin"));
        assertEquals("true", EnvSnapshotService.mask("monitor.enabled", "true"));
    }

    @Test
    void noPlaintextCredentialsInAnyOutput() {
        // 模拟凭据性键名(若未来有人往快照里加),掩码必须兜住
        Map<String, Map<String, String>> snapshot = service.snapshot();
        snapshot.values().stream().flatMap(m -> m.entrySet().stream()).forEach(entry -> {
            String lower = entry.getKey().toLowerCase();
            if (lower.contains("secret") || lower.contains("password") || lower.contains("token")
                    || lower.contains("key") || lower.contains("credential")) {
                assertEquals("******", entry.getValue(), "敏感键未掩码:" + entry.getKey());
            }
        });
    }
}
