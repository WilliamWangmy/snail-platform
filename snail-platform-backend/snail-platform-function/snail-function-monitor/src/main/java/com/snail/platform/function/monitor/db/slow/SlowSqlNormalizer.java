package com.snail.platform.function.monitor.db.slow;

/**
 * 职责:SQL 文本归一化——把可变字面量折叠成统一摘要,供慢 SQL Top 按 digestId 聚合(纯静态工具)。
 * 上游:SlowSqlCollector.recordSlow;下游:摘要键(SHA-256 前 8 位)。
 * 口径:只做空白折叠、in 列表折叠、引号字面量替换与截断;**不做数字字面量归一化**
 * (避免把 LIMIT 1000 与 LIMIT 10 合并,保留可读性);参数值、结果集、绑定变量一律不接触。
 */
public final class SlowSqlNormalizer {

    private SlowSqlNormalizer() {
    }

    /**
     * 归一化 SQL 文本:1 连续空白折叠;2 in(?,?,…)→in (?);3 单/双引号字面量→?;
     * 4 截断到 maxSqlLength(截断时末尾加 …)。
     */
    public static String normalize(String sql, int maxSqlLength) {
        if (sql == null) {
            return "";
        }
        String result = sql.replaceAll("\\s+", " ").trim();
        result = result.replaceAll("(?i)\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)", "in (?)");
        result = result.replaceAll("'(?:[^'\\\\]|\\\\.)*'", "?");
        result = result.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "?");
        if (result.length() > maxSqlLength) {
            result = result.substring(0, maxSqlLength) + "…";
        }
        return result;
    }

    /** 摘要 ID:归一化文本 SHA-256 前 8 位 hex */
    public static String digest(String normalized) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 必备算法,不可达
            throw new IllegalStateException(e);
        }
    }
}
