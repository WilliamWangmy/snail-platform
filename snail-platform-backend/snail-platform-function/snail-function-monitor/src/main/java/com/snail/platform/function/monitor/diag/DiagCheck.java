package com.snail.platform.function.monitor.diag;

import java.time.LocalDateTime;

/**
 * 职责:诊断检查项契约——12 项检查的统一签名;key 冻结(主方案 §5.4),run 永不抛异常(异常自转为 fail)。
 * 上游:CheckRegistry(并行执行);下游:12 个 checks/* 实现。
 * 口径:status 用字符串 pass/warn/fail/skip;latencyMs 为单项耗时;detail 是人话结论;
 * suggestion 是固定文案表的排障建议(前端直接展示)。
 */
public interface DiagCheck {

    /** 契约 key,冻结 */
    String key();

    /** 中文名 */
    String name();

    /** 执行检查;永不抛异常,异常自转为 fail */
    Item run();

    /**
     * 单项检查结果:key/name/status/latencyMs/detail/suggestion。
     */
    record Item(
            /** 契约 key */
            String key,
            /** 中文名 */
            String name,
            /** pass/warn/fail/skip */
            String status,
            /** 耗时(ms) */
            Long latencyMs,
            /** 结论详情 */
            String detail,
            /** 排障建议(固定文案表) */
            String suggestion) {

        public static Item pass(String key, String name, Long latencyMs, String detail, String suggestion) {
            return new Item(key, name, "pass", latencyMs, detail, suggestion);
        }

        public static Item warn(String key, String name, Long latencyMs, String detail, String suggestion) {
            return new Item(key, name, "warn", latencyMs, detail, suggestion);
        }

        public static Item fail(String key, String name, Long latencyMs, String detail, String suggestion) {
            return new Item(key, name, "fail", latencyMs, detail, suggestion);
        }

        public static Item skip(String key, String name, String detail, String suggestion) {
            return new Item(key, name, "skip", null, detail, suggestion);
        }

        /** 检查时间(序列化辅助,不入 items JSON) */
        public LocalDateTime checkedAt() {
            return LocalDateTime.now().withNano(0);
        }
    }
}
