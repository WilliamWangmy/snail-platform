package com.snail.platform.function.auth.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.redis.SnailRateGuard;
import com.snail.platform.function.auth.dto.CaptchaVO;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:图形验证码的生成、按来源限流与一次性核销(Hutool 画图,答案只存 Valkey,不落表不落日志)。
 * 上游:AuthController 的白名单生成端点、AuthService 校验链第一步;下游:StringRedisTemplate 答案位、
 * SnailRateGuard 两个计数桶(生成频次、校验失败次数)。
 * 口径:两分钟有效、取回即删——校验无论对错都消耗,防同一码重放;
 * 生成限流挡住"无限刷图占内存",失败限流挡住"拿一次生成的码穷举答案",两者都按来源 IP 计,
 * 与账号锁定无关——验证码失败不该把账号锁死,反之亦然。
 */
@Service
public class CaptchaService {

    /** Valkey 答案键前缀 */
    private static final String KEY_PREFIX = "snail:auth:captcha:";

    /** 同一来源的生成频次计数前缀 */
    private static final String ISSUE_PREFIX = "snail:auth:captcha-issue:";

    /** 同一来源的校验失败计数前缀 */
    private static final String FAIL_PREFIX = "snail:auth:captcha-fail:";

    /** 有效期 */
    private static final Duration TTL = Duration.ofMinutes(2);

    /** 生成频次窗口与阈值:一分钟 20 张,足够真人反复刷新,刷图则被拒 */
    private static final Duration ISSUE_WINDOW = Duration.ofMinutes(1);
    private static final int MAX_ISSUES_PER_WINDOW = 20;

    /** 校验失败窗口与阈值:十分钟 10 次,和账号锁定互不干扰 */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_FAILS_PER_WINDOW = 10;

    /** Redis 存取模板 */
    private final StringRedisTemplate redisTemplate;

    /** 来源维度的限流计数 */
    private final SnailRateGuard rateGuard;

    public CaptchaService(StringRedisTemplate redisTemplate, SnailRateGuard rateGuard) {
        this.redisTemplate = redisTemplate;
        this.rateGuard = rateGuard;
    }

    /** 生成一张线扰验证码并登记答案;同一来源刷图超限即拒 */
    public CaptchaVO generate(String ip) {
        String key = ISSUE_PREFIX + clientKey(ip);
        if (rateGuard.isLocked(key, MAX_ISSUES_PER_WINDOW)) {
            throw BizException.badRequest("验证码获取过于频繁,请稍后再试");
        }
        rateGuard.record(key, ISSUE_WINDOW);
        // 120x40 画布、4 位字符、10 条干扰线;位数要与前端登录页的 len=4 校验一致
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 10);
        String captchaKey = IdUtil.fastSimpleUUID();
        redisTemplate.opsForValue().set(KEY_PREFIX + captchaKey, captcha.getCode(), TTL);
        return new CaptchaVO(captchaKey, "data:image/png;base64," + captcha.getImageBase64());
    }

    /** 核销式校验:过期、错误都直接拒绝;同一来源错误次数超限后不再比对答案 */
    public void verify(String ip, String captchaKey, String captchaCode) {
        String failKey = FAIL_PREFIX + clientKey(ip);
        if (rateGuard.isLocked(failKey, MAX_FAILS_PER_WINDOW)) {
            throw new LoginRejects.Captcha("验证码错误次数过多,请稍后再试");
        }
        if (StrUtil.isBlank(captchaKey) || StrUtil.isBlank(captchaCode)) {
            countFail(failKey);
            throw new LoginRejects.Captcha("请输入验证码");
        }
        // GETDEL 原子核销:同一码无论答案对错都只可能被取到一次,并发重放也只有一个请求能拿到答案
        String cached = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + captchaKey);
        if (cached == null) {
            countFail(failKey);
            throw new LoginRejects.Captcha("验证码已过期,请重新获取");
        }
        if (!cached.equalsIgnoreCase(captchaCode.trim())) {
            countFail(failKey);
            throw new LoginRejects.Captcha("验证码错误");
        }
        // 通过即清零本 IP 的失败计数,历史失败不累积到下一次
        rateGuard.clear(failKey);
    }

    private void countFail(String failKey) {
        rateGuard.record(failKey, FAIL_WINDOW);
    }

    /** 来源键:空值归一成 unknown,避免拼出前缀相同的歧义键 */
    private static String clientKey(String ip) {
        return StrUtil.isBlank(ip) ? "unknown" : ip;
    }
}
