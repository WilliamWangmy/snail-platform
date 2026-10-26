package com.snail.platform.function.auth.service;

import com.snail.platform.framework.core.exception.BizException;

/**
 * 职责:登录校验链的失败分类——同一条 BizException 文本之下,三类失败的处置手段完全不同。
 * 凭证(口令错/账号不存在)进 LoginFailGuard 的 IP+用户名桶;验证码进 CaptchaService 的 IP 桶;
 * 准入(租户停用/到期、账号停用)是运营状态,重试无意义,一律不计数。
 * 上游:AuthService、CaptchaService;下游:仍然按 BizException 被 GlobalExceptionHandler 翻成响应,
 * 分类只服务计数,不改变对外文案与错误码。
 */
final class LoginRejects {

    private LoginRejects() {
    }

    /** 凭证维度失败:账号或密码错误 */
    static class Credential extends BizException {
        Credential(String message) {
            super(400, message);
        }
    }

    /** 验证码维度失败:缺失、过期或答案不对 */
    static class Captcha extends BizException {
        Captcha(String message) {
            super(400, message);
        }
    }

    /** 准入维度失败:租户或账号处于停用/到期状态,不计任何失败数 */
    static class Access extends BizException {
        Access(String message) {
            super(400, message);
        }
    }
}
