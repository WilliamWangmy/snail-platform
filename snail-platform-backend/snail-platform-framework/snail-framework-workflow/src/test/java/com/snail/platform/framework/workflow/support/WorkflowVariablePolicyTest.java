package com.snail.platform.framework.workflow.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.snail.platform.framework.core.exception.BizException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 职责:流程变量准入口径的离线单测——把"只收标量 + 上限"钉成可执行契约。
 * 上游:WorkflowEngineGateway 的发起与办理入参;下游:WorkflowVariablePolicy。
 * 口径:流程变量会被序列化进引擎历史并跨版本、跨线程读取,平台已经踩过"Hutool 不序列化 record"的坑,
 * 所以对象型一律拒；断言点名变量键,拒绝才可能被自助排查。
 */
class WorkflowVariablePolicyTest {

    /** 六种标量原样放行,并返回不可变副本 */
    @Test
    void acceptsScalarsAndReturnsImmutableCopy() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("businessTitle", "请假单");
        input.put("days", 3);
        input.put("daysLong", 3L);
        input.put("amount", 1200.5D);
        input.put("budget", new BigDecimal("1200.50"));
        input.put("urgent", Boolean.TRUE);

        Map<String, Object> sanitized = WorkflowVariablePolicy.sanitize(input);
        assertEquals(input, sanitized);
        input.put("late", "X");
        assertEquals(6, sanitized.size(), "返回副本不应随入参继续变化");
        assertThrows(UnsupportedOperationException.class, () -> sanitized.put("x", "y"));
    }

    /** 空入参视作无变量,不抛 */
    @Test
    void treatsNullAndEmptyAsNoVariables() {
        assertEquals(Map.of(), WorkflowVariablePolicy.sanitize(null));
        assertEquals(Map.of(), WorkflowVariablePolicy.sanitize(Map.of()));
    }

    /** 对象、集合、日期一律拒:它们会把流程历史与某个类结构绑死 */
    @Test
    void rejectsStructuredValues() {
        assertRejected("list", List.of("a"));
        assertRejected("map", Map.of("k", "v"));
        assertRejected("date", LocalDate.of(2026, 10, 26));
        assertRejected("pojo", new Object());
        assertRejected("null", null);
    }

    /** 变量名形态与条数、字符串长度上限 */
    @Test
    void enforcesNameShapeAndLimits() {
        assertRejected("1abc", "X");
        assertRejected("a-b", "X");
        assertRejected("带中文", "X");
        assertRejected("a".repeat(65), "X");

        Map<String, Object> tooMany = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) {
            tooMany.put("var" + i, i);
        }
        BizException countError = assertThrows(BizException.class,
                () -> WorkflowVariablePolicy.sanitize(tooMany));
        assertTrue(countError.getMessage().contains("最多 16 个"), countError.getMessage());

        assertRejected("longText", "x".repeat(513));
    }

    /** 拒绝必须是业务码 400,且文案点名键——前端据此把红线落到具体输入框 */
    private static void assertRejected(String name, Object value) {
        Map<String, Object> single = new LinkedHashMap<>();
        single.put(name, value);
        BizException error = assertThrows(BizException.class,
                () -> WorkflowVariablePolicy.sanitize(single), "变量 " + name + " 应被拒绝");
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains(name), error.getMessage());
    }
}
