package com.snail.platform.framework.core.response;

/**
 * 职责:全部 HTTP 响应的唯一信封,code/message/data 三段;成功 code=0,失败为非 0 业务码。
 * 上游:各 Controller 与 GlobalExceptionHandler;下游:前端与开放接口消费方。
 * 不做:分页信封另立(分页在 data 里带 rows/total,由 PageResult 承载)。
 */
public record R<T>(int code, String message, T data) {

    /** 成功码,全库唯一约定 */
    public static final int SUCCESS = 0;

    /** 成功并携带数据 */
    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, "ok", data);
    }

    /** 成功且无数据(写操作、删除操作的常规返回) */
    public static R<Void> ok() {
        return new R<>(SUCCESS, "ok", null);
    }

    /** 业务失败:code 为业务码,message 为可直接展示给使用者的文案 */
    public static R<Void> fail(int code, String message) {
        return new R<>(code, message, null);
    }
}
