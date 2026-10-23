package com.snail.platform.function.auth.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.auth.dto.CaptchaVO;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 职责:图形验证码的生成与一次性核销(Hutool 画图,答案只存 Valkey,不落表不落日志)。
 * 上游:AuthController 的白名单端点;下游:AuthService 校验链第一步。
 * 口径:两分钟有效、取回即删——校验无论对错都消耗,防同一码重放。
 */
@Service
public class CaptchaService {

    /** Valkey 键前缀 */
    private static final String KEY_PREFIX = "snail:auth:captcha:";

    /** 有效期 */
    private static final Duration TTL = Duration.ofMinutes(2);

    /**  Redis 存取模板 */
    private final StringRedisTemplate redisTemplate;

    public CaptchaService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 生成一张线扰验证码并登记答案 */
    public CaptchaVO generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 10);
        String key = IdUtil.fastSimpleUUID();
        redisTemplate.opsForValue().set(KEY_PREFIX + key, captcha.getCode(), TTL);
        return new CaptchaVO(key, "data:image/png;base64," + captcha.getImageBase64());
    }

    /** 核销式校验:过期、错误都直接拒绝 */
    public void verify(String captchaKey, String captchaCode) {
        if (StrUtil.isBlank(captchaKey) || StrUtil.isBlank(captchaCode)) {
            throw BizException.badRequest("请输入验证码");
        }
        String cached = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + captchaKey);
        if (cached == null) {
            throw BizException.badRequest("验证码已过期,请重新获取");
        }
        if (!cached.equalsIgnoreCase(captchaCode.trim())) {
            throw BizException.badRequest("验证码错误");
        }
    }
}
