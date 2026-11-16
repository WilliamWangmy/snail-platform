package com.snail.platform.function.monitor.db.slow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 职责:SQL 归一化纯单测——空白折叠、in 列表折叠、引号字面量替换、截断与摘要稳定性(§5.2.3)。
 * 上游:SlowSqlNormalizer 静态工具;下游:SlowSqlCollector digest 键。
 * 口径:不做数字字面量归一化(LIMIT 1000 与 LIMIT 10 不合并);摘要恒为 8 位 hex。
 */
class SlowSqlNormalizerTest {

    @Test
    void collapsesWhitespace() {
        assertEquals("SELECT * FROM t WHERE a = ?",
                SlowSqlNormalizer.normalize("SELECT  *\n  FROM\tt   WHERE a = ?", 500));
    }

    @Test
    void foldsInListPlaceholders() {
        // 折叠目标统一为小写 "in (?)" 文本,大小写均可命中
        assertEquals("SELECT * FROM t WHERE id in (?)",
                SlowSqlNormalizer.normalize("SELECT * FROM t WHERE id in (?, ?, ?, ?)", 500));
        assertEquals("SELECT * FROM t WHERE id in (?)",
                SlowSqlNormalizer.normalize("SELECT * FROM t WHERE id IN ( ? , ? )", 500));
    }

    @Test
    void replacesQuotedLiterals() {
        assertEquals("UPDATE u SET name = ? WHERE id = ?",
                SlowSqlNormalizer.normalize("UPDATE u SET name = '张三\"x' WHERE id = ?", 500));
        assertEquals("SELECT * FROM t WHERE c = ?",
                SlowSqlNormalizer.normalize("SELECT * FROM t WHERE c = \"abc\"", 500));
    }

    @Test
    void doesNotFoldNumericLiterals() {
        // 数字不归一化:LIMIT 1000 与 LIMIT 10 摘要不同
        assertNotEquals(
                SlowSqlNormalizer.digest(SlowSqlNormalizer.normalize("SELECT * FROM t LIMIT 1000", 500)),
                SlowSqlNormalizer.digest(SlowSqlNormalizer.normalize("SELECT * FROM t LIMIT 10", 500)));
    }

    @Test
    void truncatesWithEllipsis() {
        String longSql = "SELECT " + "x, ".repeat(400) + "1";
        String normalized = SlowSqlNormalizer.normalize(longSql, 500);
        assertEquals(501, normalized.length());
        assertTrue(normalized.endsWith("…"));
    }

    @Test
    void digestIsStableEightHex() {
        String d1 = SlowSqlNormalizer.digest(SlowSqlNormalizer.normalize("SELECT 1", 500));
        String d2 = SlowSqlNormalizer.digest(SlowSqlNormalizer.normalize("SELECT 1", 500));
        assertEquals(d1, d2);
        assertEquals(8, d1.length());
        assertTrue(d1.matches("[0-9a-f]{8}"));
    }

    @Test
    void nullOrEmptyInputYieldsEmpty() {
        assertEquals("", SlowSqlNormalizer.normalize(null, 500));
        assertEquals("", SlowSqlNormalizer.normalize("", 500));
    }
}
