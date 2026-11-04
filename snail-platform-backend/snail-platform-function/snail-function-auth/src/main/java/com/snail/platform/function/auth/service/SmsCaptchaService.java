package com.snail.platform.function.auth.service;

import com.snail.platform.framework.redis.SnailRateGuard;
import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:短信验证码的生成、登记、一次性核销与错码穷举计数(答案只存 Valkey,不落表不落日志)。
 * 形态镜像 CaptchaService:取回即删(GGETDEL)防重放,失败桶按 IP+手机号计,与账号锁定互不干扰。
 * 发送侧的四道限流闸(人机/间隔/双日额)住 AuthService.sendSmsCaptcha——它们决定"发不发",
 * 本类只管"码本身";发送成功的登记(issue)由调用方在真实发起发送前调用。
 * 上游:AuthService 的发送与登录链;下游:StringRedisTemplate 答案位、SnailRateGuard 失败桶。
 * 口径:6 位纯数字(SecureRandom),5 分钟有效;同一手机号重发即覆盖旧码——用户手里永远只有最新一码。
 */
@Service
public class SmsCaptchaService {

    /** Valkey 答案键前缀 */
    private static final String KEY_CODE_PREFIX = "snail:auth:sms:";

    /** 错码失败计数前缀:来源 + 手机号 */
    private static final String KEY_FAIL_PREFIX = "snail:auth:sms-fail:";

    /** 验证码有效期 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    /** 验证码位数 */
    private static final int CODE_LENGTH = 6;

    /** 错码失败窗口与阈值:十分钟 10 次,和账号锁定互不干扰 */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_FAILS_PER_WINDOW = 10;

    /** 同一手机号的发送间隔:60 秒 1 条 */
    private static final Duration SEND_INTERVAL_WINDOW = Duration.ofMinutes(1);
    private static final String KEY_INTERVAL_PREFIX = "snail:auth:sms-interval:";

    /** 同一手机号的日发送额度:24 小时 10 条 */
    private static final Duration PHONE_DAILY_WINDOW = Duration.ofHours(24);
    private static final int MAX_SEND_PER_PHONE_DAILY = 10;
    private static final String KEY_PHONE_DAILY_PREFIX = "snail:auth:sms-daily:";

    /** 同一来源 IP 的日发送额度:24 小时 20 条 */
    private static final Duration IP_DAILY_WINDOW = Duration.ofHours(24);
    private static final int MAX_SEND_PER_IP_DAILY = 20;
    private static final String KEY_IP_DAILY_PREFIX = "snail:auth:sms-ip-daily:";

    /** 超限统一文案:不区分命中哪道闸,不给攻击者反馈探测面 */
    public static final String SEND_FREQUENT_MESSAGE = "短信发送过于频繁,请稍后再试";

    /** 随机源:验证码唯一随机性所在,复用同一实例 */
    private final SecureRandom random = new SecureRandom();

    /** Redis 存取模板 */
    private final StringRedisTemplate redisTemplate;

    /** 错码穷举计数 */
    private final SnailRateGuard rateGuard;

    public SmsCaptchaService(StringRedisTemplate redisTemplate, SnailRateGuard rateGuard) {
        this.redisTemplate = redisTemplate;
        this.rateGuard = rateGuard;
    }

    /**
     * 生成验证码并登记答案;调用方在通过全部发送闸、确认要真实发送时调用,
     * 码值只在返回值里出现一次(进短信参数),任何日志不得输出。
     */
    public String issue(String phone) {
        String code = String.format("%0" + CODE_LENGTH + "d", random.nextInt(1_000_000));
        redisTemplate.opsForValue().set(KEY_CODE_PREFIX + phone, code, CODE_TTL);
        return code;
    }

    /**
     * 核销式校验:GETDEL 原子取回即删,同一码无论对错只可能被取到一次,并发重放也只有一个请求拿到答案。
     * 失败按 IP+手机号计数,超限后不再比对答案;通过即清零该来源的历史失败。
     * 错码属于凭证失败(LoginRejects.Credential):由调用方计入 IP+手机号锁定,与图形验证码口径分离。
     */
    public void verify(String ip, String phone, String smsCode) {
        String failKey = KEY_FAIL_PREFIX + clientKey(ip) + ":" + phone;
        if (rateGuard.isLocked(failKey, MAX_FAILS_PER_WINDOW)) {
            throw new LoginRejects.Credential("验证码错误次数过多,请稍后再试");
        }
        if (smsCode == null || smsCode.isBlank()) {
            countFail(failKey);
            throw new LoginRejects.Credential("请输入短信验证码");
        }
        String cached = redisTemplate.opsForValue().getAndDelete(KEY_CODE_PREFIX + phone);
        if (cached == null) {
            countFail(failKey);
            throw new LoginRejects.Credential("验证码已过期,请重新获取");
        }
        if (!cached.equals(smsCode.trim())) {
            countFail(failKey);
            throw new LoginRejects.Credential("验证码错误");
        }
        rateGuard.clear(failKey);
    }

    /**
     * 发送侧三道限流闸(人机闸归 CaptchaService,在调用方先行):同手机号 60 秒 1 条、
     * 手机号日额 10 条、IP 日额 20 条。按尝试计账——闸一过就记数,后面的定位/发送失败不退还额度,
     * 防止用"改参数重试"把额度刷成无限;超限统一文案,不区分命中哪道闸。
     */
    public void checkSendQuota(String ip, String phone) {
        String intervalKey = KEY_INTERVAL_PREFIX + phone;
        if (rateGuard.isLocked(intervalKey, 1)) {
            throw new LoginRejects.Credential(SEND_FREQUENT_MESSAGE);
        }
        rateGuard.record(intervalKey, SEND_INTERVAL_WINDOW);
        String phoneDailyKey = KEY_PHONE_DAILY_PREFIX + phone;
        if (rateGuard.isLocked(phoneDailyKey, MAX_SEND_PER_PHONE_DAILY)) {
            throw new LoginRejects.Credential(SEND_FREQUENT_MESSAGE);
        }
        rateGuard.record(phoneDailyKey, PHONE_DAILY_WINDOW);
        String ipDailyKey = KEY_IP_DAILY_PREFIX + clientKey(ip);
        if (rateGuard.isLocked(ipDailyKey, MAX_SEND_PER_IP_DAILY)) {
            throw new LoginRejects.Credential(SEND_FREQUENT_MESSAGE);
        }
        rateGuard.record(ipDailyKey, IP_DAILY_WINDOW);
    }

    private void countFail(String failKey) {
        rateGuard.record(failKey, FAIL_WINDOW);
    }

    /** 来源键:空值归一成 unknown,避免拼出前缀相同的歧义键 */
    private static String clientKey(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip;
    }
}
