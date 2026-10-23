package com.snail.platform.framework.db;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.query.PageQuery;
import java.util.Map;

/**
 * 职责:分页单点装配——把各域 XxxQuery 装配成 MyBatis-Plus 分页对象;排序只准走本类的白名单通道
 * (orderBy 令牌→域内提供的列映射),白名单外字段直接拒绝,防排序参数退化成 SQL 注入面。
 * 上游:各域 service 的 page/export;下游:MP 分页拦截器消费 Page、Lambda 条件构造器追加排序。
 * 口径:深翻保护、行数上限等后续分页机制只挂 from() 这一处,不回改各域契约;
 * 各域首版不接排序(固定排序仍写死在 service 的 orderByDesc),接排序=该域自备白名单 Map 并调 applySort。
 */
public final class PageAssembler {

    private PageAssembler() {
    }

    /** 按 PageQuery 装配 MP 分页对象;页码与条数上限已在各域 Query 侧校验完成 */
    public static <T> Page<T> from(PageQuery query) {
        return new Page<>(query.pageNum(), query.pageSize());
    }

    /**
     * 排序收口:orderBy 形如 "field" 或 "field:asc"/"field:desc",缺省方向 desc(列表惯例最新在前);
     * 空值表示不加排序,保留 service 内既有固定排序;字段不在白名单或方向非法即 400,不透传任何原始字符串进 SQL。
     */
    public static <T> void applySort(LambdaQueryWrapper<T> wrapper, String orderBy,
                                     Map<String, SFunction<T, ?>> sortable) {
        if (orderBy == null || orderBy.isBlank()) {
            return;
        }
        String field = orderBy;
        boolean desc = true;
        int sep = orderBy.indexOf(':');
        if (sep >= 0) {
            field = orderBy.substring(0, sep).trim();
            String dir = orderBy.substring(sep + 1).trim();
            if ("asc".equalsIgnoreCase(dir)) {
                desc = false;
            } else if (!"desc".equalsIgnoreCase(dir)) {
                throw BizException.badRequest("排序方向只能是 asc 或 desc");
            }
        }
        SFunction<T, ?> column = sortable.get(field);
        if (column == null) {
            throw BizException.badRequest("排序字段不在白名单内: " + field);
        }
        if (desc) {
            wrapper.orderByDesc(column);
        } else {
            wrapper.orderByAsc(column);
        }
    }
}
