package com.snail.platform.framework.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 职责:租户桶名归一的纯单测——逐字保留、S3 硬规则(相邻点)、防御性归一、长度封顶各自钉桩。
 * 上游:S3BucketNames;口径见彼处:点原样保留与租户标识逐字对应,相邻点坍缩,下划线防御归一,总长 63。
 */
class S3BucketNamesTest {

    @Test
    void keepsCodeVerbatimAndAppendsTenantId() {
        assertEquals("snail-acme-3", S3BucketNames.forTenant("acme", 3));
        assertEquals("snail-sh.001-7", S3BucketNames.forTenant("sh.001", 7), "点原样保留,桶名与标识逐字对应");
        assertEquals("snail-my-team-9", S3BucketNames.forTenant("my-team", 9), "连字符原样保留");
    }

    @Test
    void collapsesAdjacentDotsAndNormalizesUnderscore() {
        assertEquals("snail-a.b-1", S3BucketNames.forTenant("a..b", 1), "相邻点是 S3 硬性非法,坍缩为单点");
        assertEquals("snail-a-b-2", S3BucketNames.forTenant("a_b", 2), "下划线不在租户标识字符集内,防御性归一");
    }

    @Test
    void capsLengthAt63AndKeepsEndsAlphanumeric() {
        String code = "a".repeat(80);
        String bucket = S3BucketNames.forTenant(code, 42);
        assertTrue(bucket.length() <= 63, "桶名不得超过 63: " + bucket.length());
        assertTrue(bucket.endsWith("-42"), "超长截断后租户 ID 段必须完整保留");
        assertTrue(bucket.matches("[a-z0-9].*[a-z0-9]"), "首尾必须是字母或数字: " + bucket);
    }

    @Test
    void truncationEndingOnDotOrHyphenIsStripped() {
        // 截断尾落在点/连字符上:去尾后再拼 ID 段,桶名尾仍是字母数字,且无相邻点
        String code = "a" + ".".repeat(50);
        String bucket = S3BucketNames.forTenant(code, 42);
        assertFalse(bucket.contains(".."), "相邻点不得进入桶名: " + bucket);
        assertTrue(bucket.matches(".*[a-z0-9]"), "桶名尾必须是字母或数字: " + bucket);
    }

    @Test
    void shortCodeStillMeetsMinimumLength() {
        String bucket = S3BucketNames.forTenant("a", 9);
        assertEquals("snail-a-9", bucket);
        assertTrue(bucket.length() >= 3, "前缀加 ID 保证最短桶名也过 3 字符线");
    }
}
