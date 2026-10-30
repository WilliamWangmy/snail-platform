package com.snail.platform.framework.core.response;

import java.util.List;

/**
 * 职责:分页数据载体,塞在 R 的 data 里——rows 是当前页行集,total 是满足条件的总行数。
 * 上游:service 层的分页查询;下游:Controller 出参。
 * 口径:页码与条数属于入参(见 PageQuery),本类只承载结果,不含页码字段。
 */
public record PageResult<T>(List<T> rows, long total) {

    /** 组装分页结果;rows 为 null 时由服务端自行规整,本类不做兜底 */
    public static <T> PageResult<T> of(List<T> rows, long total) {
        return new PageResult<>(rows, total);
    }
}
