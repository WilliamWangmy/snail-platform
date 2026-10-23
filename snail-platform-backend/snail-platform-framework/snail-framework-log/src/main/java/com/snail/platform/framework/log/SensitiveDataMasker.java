package com.snail.platform.framework.log;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 职责:param/result JSON 落库前的脱敏单点——名单内字段的字符串值一律替换为 ******。
 * 上游:OperLogAspect;下游:sys_oper_log.param / result 列。
 * 口径:名单是约定而非穷举(password、手机号、证件号、验证码类),新增敏感字段只改 SENSITIVE_KEYS 一处;
 * 只处理字符串值——这些字段在 DTO 里本就是 String,数值型不存在"泄露原文"的形态。
 */
public final class SensitiveDataMasker {

    /** 脱敏替换值 */
    public static final String MASK = "******";

    /** 敏感字段名单:整键精确匹配、不区分大小写,不做子串(防把 passwordPolicy 这类配置键误伤) */
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "newPassword", "oldPassword", "rawPassword",
            "phone", "mobile", "idCard", "idNumber", "bankCard",
            "captchaCode");

    /** 由名单一次性拼出的替换正则:捕获 "键" 与冒号,吃掉紧随其后的字符串值 */
    private static final Pattern SENSITIVE_PAIR = Pattern.compile(
            "(\"(?:" + String.join("|", SENSITIVE_KEYS) + ")\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private SensitiveDataMasker() {
    }

    /**
     * 对 JSON 文本做键级脱敏:`"key":"value"` 且 key 命中名单时值替换为 ******。
     * 直接扫原文而非反解析对象——切面拿到的已是序列化字符串,为脱敏再建对象树不值当;
     * 值只吃连续非引号字符,含转义引号的异常形态会被整体替换,不产生半脱敏。
     */
    public static String mask(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        return SENSITIVE_PAIR.matcher(json).replaceAll("$1\"" + MASK + "\"");
    }
}
