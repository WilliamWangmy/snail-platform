package com.snail.platform.framework.storage;

import java.util.regex.Pattern;

/**
 * 职责:业务标识到 S3 桶名的归一工具——S3 桶名 3-63、小写字母/数字/连字符/点,
 * 点不能相邻、首尾须字母数字;归一规则收口在这一个点,租户开通等场景不各写各的。
 * 口径:租户标识字符集(小写字母/数字/点/连字符)本就 S3 合法,点原样保留
 * (桶名 snail-<code>-<tenantId> 与租户标识逐字对应,path-style 寻址下点无寻址歧义);
 * 仅处理 S3 硬规则——相邻点坍缩为单点;下划线不在新字符集内,防御性归一为连字符;
 * 租户 ID 后缀兜底唯一,前缀 snail- 留出平台命名空间,共享 MinIO 里不与其他应用撞车。
 */
public final class S3BucketNames {

    /** S3 桶名长度上限 */
    private static final int BUCKET_MAX_LENGTH = 63;

    /** 平台命名空间前缀 */
    private static final String PREFIX = "snail-";

    /** 防御性归一:字符集外的下划线(历史/手输)按连字符处理 */
    private static final Pattern UNSAFE_CHARS = Pattern.compile("_");

    /** S3 硬规则:相邻点坍缩为单点 */
    private static final Pattern ADJACENT_DOTS = Pattern.compile("\\.{2,}");

    private S3BucketNames() {
    }

    /**
     * 租户桶名:{snail-}{归一化租户标识}{-租户ID},总长截到 63。
     * 截断只压标识段;截断尾可能落在点或连字符上,而桶名尾必须是字母数字,去尾后再拼接
     * (拼接段以连字符开头、数字结尾,首尾合法性天然成立)。
     */
    public static String forTenant(String tenantCode, long tenantId) {
        String suffix = "-" + tenantId;
        int codeMax = BUCKET_MAX_LENGTH - PREFIX.length() - suffix.length();
        String safe = UNSAFE_CHARS.matcher(tenantCode.trim().toLowerCase()).replaceAll("-");
        safe = ADJACENT_DOTS.matcher(safe).replaceAll(".");
        if (safe.length() > codeMax) {
            safe = safe.substring(0, codeMax);
        }
        while (safe.endsWith("-") || safe.endsWith(".")) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return PREFIX + safe + suffix;
    }
}
