package com.snail.platform.function.system.user.service;

import com.snail.platform.framework.core.exception.BizException;

/**
 * 职责:口令强度的服务端单点口径——长度 8-30,与各 DTO 上的 @Size 注解同口径兜底;
 * 新增用户、重置口令、自助改密三条写路径共用,改口径只动这里。
 * 上游:UserService/ProfileService 的口令写路径;下游:无,纯校验。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** 校验口令强度,不合格抛业务拒绝 */
    public static void validate(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8 || rawPassword.length() > 30) {
            throw BizException.badRequest("密码长度 8-30");
        }
    }
}
