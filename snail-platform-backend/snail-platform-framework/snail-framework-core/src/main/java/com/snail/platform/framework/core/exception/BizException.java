package com.snail.platform.framework.core.exception;

/**
 * 职责:业务失败的统一异常载体,携带业务码与人可读文案;GlobalExceptionHandler 负责翻成 R。
 * 上游:service 层的拒绝分支;下游:web 层异常处理。
 * 约定:系统级故障不要用本类——那是未预期异常,统一按 500 兜底并记 error 日志。
 */
public class BizException extends RuntimeException {

    /** 业务错误码,非 0 */
    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    /** 参数或操作被拒绝:400 */
    public static BizException badRequest(String message) {
        return new BizException(400, message);
    }

    /** 未认证或凭证失效:401 */
    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    /** 已认证但无权限:403 */
    public static BizException forbidden(String message) {
        return new BizException(403, message);
    }

    public int getCode() {
        return code;
    }
}
