package com.snail.platform.function.system.user.service;

import com.snail.platform.framework.core.exception.BizException;
import java.util.Locale;
import java.util.Set;

/**
 * 职责:口令强度的服务端单点口径——长度 8-20、四类字符全含、拒绝常见弱口令;
 * 新增用户、重置口令、自助改密、租户开通、强制改密五条写路径共用,改口径只动这里,DTO 的 @Size 只做同区间兜底。
 * 上游:UserService/ProfileService/TenantService 的口令写路径;下游:无,纯校验。
 * 口径:黑名单只收"公开口令表"里的高频值,不是完整字典;前端 src/utils/password.ts 持有同口径副本,改黑名单或文案必须两侧同步。
 */
public final class PasswordPolicy {

    /** 最短长度 */
    private static final int MIN_LENGTH = 8;

    /** 最长长度 */
    private static final int MAX_LENGTH = 20;

    /** 必须命中的字符类别数(大写/小写/数字/符号全含) */
    private static final int MIN_CHAR_CLASSES = 4;

    /** 高频弱口令:命中即拒,不看长度是否达标 */
    private static final Set<String> COMMON_WEAK = Set.of(
            "12345678", "123456789", "1234567890", "00000000", "11111111", "88888888",
            "password", "password1", "passw0rd", "p@ssw0rd", "abc12345", "a123456",
            "admin123", "admin888", "administrator", "qwerty123", "1q2w3e4r", "1qaz2wsx",
            "iloveyou", "test1234", "guest1234", "letmein1", "monkey123", "football1");

    /** 长度不达标的统一文案 */
    private static final String LENGTH_MESSAGE = "密码长度需 8-20 位";

    /** 类别不全的统一文案 */
    private static final String CLASS_MESSAGE = "密码必须包含大写字母、小写字母、数字和特殊符号";

    private PasswordPolicy() {
    }

    /** 校验口令强度,不合格抛业务拒绝 */
    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw BizException.badRequest(LENGTH_MESSAGE);
        }
        if (rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw BizException.badRequest(LENGTH_MESSAGE);
        }
        if (rawPassword.chars().anyMatch(Character::isWhitespace)) {
            throw BizException.badRequest("密码不能包含空格等空白字符");
        }
        if (COMMON_WEAK.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            throw BizException.badRequest("密码过于常见，请更换");
        }
        if (charClasses(rawPassword) < MIN_CHAR_CLASSES) {
            throw BizException.badRequest(CLASS_MESSAGE);
        }
    }

    /** 命中的字符类别数:小写、大写、数字、其余可见符号各算一类 */
    private static int charClasses(String password) {
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        int classes = 0;
        if (lower) {
            classes++;
        }
        if (upper) {
            classes++;
        }
        if (digit) {
            classes++;
        }
        if (symbol) {
            classes++;
        }
        return classes;
    }
}
