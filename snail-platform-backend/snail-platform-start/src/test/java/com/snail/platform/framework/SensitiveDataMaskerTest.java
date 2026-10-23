package com.snail.platform.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.log.SensitiveDataMasker;
import org.junit.jupiter.api.Test;

/**
 * 职责:操作日志脱敏单点的纯单元回归——开通租户的管理员口令与联系电话不得以明文落库。
 * 上游:无 Spring 上下文;下游:涉及开通/改密的 E2E 留痕断言。
 */
class SensitiveDataMaskerTest {

    @Test
    void masksProvisioningCredentialsAndPhone() {
        String json = """
                {"tenantCode":"demo","contactPhone":"13900000000",
                 "adminUsername":"demoAdmin","adminPassword":"Admin@123"}
                """;
        String masked = SensitiveDataMasker.mask(json);
        assertFalse(masked.contains("Admin@123"), "管理员口令原文泄漏: " + masked);
        assertFalse(masked.contains("13900000000"), "联系电话原文泄漏: " + masked);
        assertTrue(masked.contains("\"adminPassword\":\"" + SensitiveDataMasker.MASK + "\""), masked);
        assertTrue(masked.contains("\"contactPhone\":\"" + SensitiveDataMasker.MASK + "\""), masked);
        assertTrue(masked.contains("\"adminUsername\":\"demoAdmin\""), "非敏感字段被误伤: " + masked);
    }

    @Test
    void keepsNamesThatMerelyContainSensitiveWords() {
        assertEquals("{\"passwordPolicy\":\"strict\",\"phoneBook\":\"all\"}",
                SensitiveDataMasker.mask("{\"passwordPolicy\":\"strict\",\"phoneBook\":\"all\"}"));
    }
}
