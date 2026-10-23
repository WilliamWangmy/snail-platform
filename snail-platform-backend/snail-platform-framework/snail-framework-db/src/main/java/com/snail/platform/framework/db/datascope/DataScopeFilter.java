package com.snail.platform.framework.db.datascope;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 职责:数据权限谓词的一次判定结果——"可见组织 ID 集"与"本人单据"两臂的 OR 并集,任一臂为 null 即该臂不参与。
 * 由业务侧的判定服务在请求线程算好,经 DataScopeContextHolder 开窗交给 SnailDataScopeInnerInterceptor 注入 SQL;
 * 组织臂取值 1 全部档时根本不开窗,本对象只表达"有限可见"的三种形态。
 * 上游:function 侧 DataScope 判定服务;下游:framework-db 拦截器。
 * 约束:列名会被拼进 SQL,构造即按小写下划线白名单校验;ID 一律 BIGINT 数值,无注入面。
 */
public record DataScopeFilter(List<Long> orgIds, Long creatorId, String orgColumn, String creatorColumn) {

    /** 默认组织列:base 与业务库单据共用的快照列名 */
    public static final String DEFAULT_ORG_COLUMN = "org_id";

    /** 默认经办人列:scope 本人档消费的审计列 */
    public static final String DEFAULT_CREATOR_COLUMN = "create_by";

    private static final Pattern COLUMN_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    public DataScopeFilter {
        if (!COLUMN_NAME.matcher(orgColumn).matches() || !COLUMN_NAME.matcher(creatorColumn).matches()) {
            throw new IllegalArgumentException("数据权限列名只允许小写字母/数字/下划线: " + orgColumn + ", " + creatorColumn);
        }
    }

    public static DataScopeFilter of(List<Long> orgIds, Long creatorId) {
        return new DataScopeFilter(orgIds, creatorId, DEFAULT_ORG_COLUMN, DEFAULT_CREATOR_COLUMN);
    }

    /** 两臂都无内容 = 无过滤意义;判定服务不该产出这种对象,拦截器遇到按不开窗处理 */
    public boolean isEmpty() {
        return (orgIds == null || orgIds.isEmpty()) && creatorId == null;
    }
}
