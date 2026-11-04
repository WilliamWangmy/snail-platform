package com.snail.platform.function.external.api;

/**
 * 职责:第三方调用失败的统一异常载体——端口契约的一部分,消费方按语义决定吞还是抛。
 * 失败语义分档(外部网关设计 §4):邮件/短信是"送达承诺",失败必须上抛由调用方(或 notify 投递器)
 * 记录重试;IP 定位类"装饰信息"由端口实现捕获后静默降级,消费方看不到本异常。
 * 上游:external 各客户端适配器;下游:notify 出站通道、将来的 auth 验证码与 system 登录日志。
 */
public class ExternalCallException extends RuntimeException {

    /** 厂商标识,与流水表 vendor 列同源 */
    private final String vendor;

    /** 动作标识,与流水表 action 列同源 */
    private final String action;

    public ExternalCallException(String vendor, String action, String message) {
        super(message);
        this.vendor = vendor;
        this.action = action;
    }

    public ExternalCallException(String vendor, String action, String message, Throwable cause) {
        super(message, cause);
        this.vendor = vendor;
        this.action = action;
    }

    /** 把厂商 SDK/协议的任意异常归一为本异常:原因串截断进文案,保留原始堆栈供排查 */
    public static ExternalCallException wrap(String vendor, String action, Throwable cause) {
        String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new ExternalCallException(vendor, action, reason, cause);
    }

    public String getVendor() {
        return vendor;
    }

    public String getAction() {
        return action;
    }
}
