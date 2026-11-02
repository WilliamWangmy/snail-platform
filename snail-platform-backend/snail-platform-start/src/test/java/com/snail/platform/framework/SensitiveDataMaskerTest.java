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

    /**
     * 转义引号半脱敏回归:值内含 \" 时,旧正则 [^"]* 会在第一个引号处截断,
     * 残留 cd\" 形态的后半段明文进审计表。修复后的转义感知形态必须整体替换。
     */
    @Test
    void masksValuesContainingEscapedQuotes() {
        String json = "{\"password\":\"ab\\\"cd\",\"username\":\"u1\"}";
        String masked = SensitiveDataMasker.mask(json);
        assertFalse(masked.contains("ab\\\"cd"), "转义引号口令原文泄漏: " + masked);
        assertFalse(masked.contains("cd"), "口令后半段残留: " + masked);
        assertTrue(masked.contains("\"password\":\"" + SensitiveDataMasker.MASK + "\""), masked);
        assertTrue(masked.contains("\"username\":\"u1\""), "非敏感字段被误伤: " + masked);
    }

    /** 值内含转义反斜杠与正常转义对时同样整体替换,后续字段不受误伤 */
    @Test
    void masksValuesWithOtherEscapes() {
        String json = "{\"oldPassword\":\"a\\\\b\\\"c\",\"captchaCode\":\"8 8\"}";
        String masked = SensitiveDataMasker.mask(json);
        assertFalse(masked.contains("a\\\\b") || masked.contains("\\\"c"), "转义形态原文泄漏: " + masked);
        assertTrue(masked.contains("\"oldPassword\":\"" + SensitiveDataMasker.MASK + "\""), masked);
        assertTrue(masked.contains("\"captchaCode\":\"" + SensitiveDataMasker.MASK + "\""), masked);
    }
}
