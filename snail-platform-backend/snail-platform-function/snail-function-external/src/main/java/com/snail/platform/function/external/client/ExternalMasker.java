package com.snail.platform.function.external.client;

import java.util.regex.Pattern;

/**
 * 职责:第三方流水落库前的地址脱敏单点——邮箱与手机号按位打码,密钥类字段根本不进快照。
 * 与 SensitiveDataMasker 的分工:那个按 JSON 键名整体替换值,本类按地址形态部分保留(排查要看得出是哪个人/哪个号),
 * 两者叠加使用:客户端先用本类打码地址,再经 SensitiveDataMasker 兜底键级脱敏。
 * 上游:SmtpMailClient 及将来的短信/IP 客户端;下游:调用流水 request/response 列。
 */
public final class ExternalMasker {

    /** 邮箱形态:本地部分只留首字符 */
    private static final Pattern EMAIL = Pattern.compile("^(.).*(@.+)$");

    /** 手机号形态:大陆 11 位为主,前三后四保留 */
    private static final Pattern PHONE = Pattern.compile("^(\\d{3})\\d*(\\d{4})$");

    private ExternalMasker() {
    }

    /** 邮箱打码:a***@domain.com;非邮箱形态原样返回(调用方应先过滤非法地址,这里是兜底) */
    public static String maskEmail(String address) {
        if (address == null || address.isBlank()) {
            return address;
        }
        return EMAIL.matcher(address.trim()).replaceAll("$1***$2");
    }

    /** 手机号打码:138****1234;位数不足 7 的短串整串打码,不留可辨识片段 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        String trimmed = phone.trim();
        return PHONE.matcher(trimmed).replaceAll("$1****$2");
    }
}
