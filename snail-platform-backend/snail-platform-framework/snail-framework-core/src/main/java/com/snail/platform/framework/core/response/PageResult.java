package com.snail.platform.framework.core.response;

import java.util.List;

/**
 * 职责:分页数据载体,塞在 R 的 data 里;页码从 1 起。
 * 上游:service 层的分页查询;下游:Controller 出参。
 */
public record PageResult<T>(List<T> rows, long total) {

    public static <T> PageResult<T> of(List<T> rows, long total) {
        return new PageResult<>(rows, total);
    }
}
