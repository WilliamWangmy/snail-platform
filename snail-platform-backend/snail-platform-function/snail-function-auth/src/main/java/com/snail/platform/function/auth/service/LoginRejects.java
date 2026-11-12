package com.snail.platform.function.auth.service;

import com.snail.platform.framework.core.exception.BizException;

/**
 * 职责:登录校验链的失败分类——同一条 BizException 文本之下,三类失败的处置手段完全不同。
 * 凭证(口令错/账号不存在)进 LoginFailGuard 的 IP+用户名桶;验证码进 CaptchaService 的 IP 桶;
 * 准入(租户停用/到期、账号停用)是运营状态,重试无意义,一律不计数。
 * 上游:AuthService、CaptchaService;下游:仍然按 BizException 被 GlobalExceptionHandler 翻成响应。
 * 分类既决定计数桶,也决定对外文案:凭证与验证码原样回各自文案(验证码在定位用户之前,不泄露账号存在性),
 * 准入类由登录链统一改写成"账号或密码错误"防枚举;Scene(端未开通)是凭证验真后才可达的例外,文案透传;
 * 错误码始终是 400。
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

    /**
     * scene 未开通的准入拒绝:Access 的透传子类——scene 闸在密码/短信码验真之后,
     * 到达此处的调用方必已持有正确凭证,「租户未开通{端名}」文案可对外透传,无枚举/探测风险;
     * 准入分类不变:不计任何失败数。
     */
    static class Scene extends Access {
        Scene(String message) {
            super(message);
        }
    }
}
