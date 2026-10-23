package com.snail.platform.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 职责:登录全链的端到端回归——验证码签发取证、验证码注入、校验链、BCrypt、JWT 签发与 /me 回验一次跑通。
 * 上游:snail-platform-start 装配后的真实应用上下文(需本机 MySQL/Valkey,经 SNAIL_DB_* 环境变量注入口令);
 * 下游:无断言外设。未设 SNAIL_DB_USERNAME 时整类跳过,不拖累离线构建。
 * 口径:验证码答案由测试经代码写入 Valkey(与 CaptchaService 同键规),不旁路校验逻辑本身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class LoginFlowTest {

    /** 全链 MVC,走真实安全过滤链 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void loginWithSeedAdminThenAccessMe() throws Exception {
        // 签发面取证:R 信封 + PNG data-uri + 答案落 Valkey。此前只验了注入面,签发端点本身从未被命中过
        String issued = mockMvc.perform(get("/system/auth/captcha"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(issued.contains("\"code\":0"), "验证码签发失败: " + issued);
        assertTrue(issued.contains("data:image/png;base64,"), "验证码图不是 PNG data-uri: " + issued);
        Matcher keyMatcher = Pattern.compile("\"captchaKey\":\"([^\"]+)\"").matcher(issued);
        assertTrue(keyMatcher.find(), "签发响应缺 captchaKey: " + issued);
        String issuedRedisKey = "snail:auth:captcha:" + keyMatcher.group(1);
        assertTrue(Boolean.TRUE.equals(redisTemplate.hasKey(issuedRedisKey)), "验证码答案未落 Valkey: " + issuedRedisKey);
        redisTemplate.delete(issuedRedisKey);

        String captchaKey = "it-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            String body = "{\"username\":\"admin\",\"password\":\"admin123\",\"captchaKey\":\""
                    + captchaKey + "\",\"captchaCode\":\"ABCD\"}";
            String response = mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response);
            assertTrue(matcher.find(), "登录响应未含令牌: " + response);
            String token = matcher.group(1);

            String me = mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(me.contains("\"username\":\"admin\""), "/me 未回显身份: " + me);
            assertTrue(me.contains("\"tenantId\":1"), "/me 未带出租户: " + me);
        } finally {
            redisTemplate.delete(redisKey);
        }
    }
}
