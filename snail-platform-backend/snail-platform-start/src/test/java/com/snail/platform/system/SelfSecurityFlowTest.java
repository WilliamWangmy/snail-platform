package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * 职责:自助安全端到端回归——token 版本吊销(改密/重置/停用/踢人后旧令牌下一次请求即 401)、
 * 自助改密防爆破(5 次锁 10 分钟)、个人档案读写、在线会话可见与强制下线、不能踢自己。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 口径:全程只用独立测试用户,不触碰 admin 口令,与其他流程测试隔离;用户与在线键有意残留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class SelfSecurityFlowTest {

    /** 全链 MVC:认证过滤器(token 版本比对 + 心跳)真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");

    private static final String PASSWORD = "User@1234";
    private static final String NEW_PASSWORD = "NewPass@123";
    private static final String RESET_PASSWORD = "Reset@12345";

    @Test
    void selfSecurityFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        // 爆破锁定用独立用户 A:5 次错误逐条计数,第 6 次命中锁定
        String userA = "selfA" + suffix;
        createUser(userA, adminToken);
        String tokenA = login(userA, PASSWORD);
        for (int i = 0; i < 5; i++) {
            String wrong = postBody("/system/profile/password",
                    "{\"oldPassword\":\"Wrong@" + i + "ab\",\"newPassword\":\"" + NEW_PASSWORD + "\"}", tokenA);
            assertNotEquals(0, jsonCode(wrong), "错误原密码应被拒: " + wrong);
            assertTrue(wrong.contains("原密码不正确"), "第 " + (i + 1) + " 次错误应报原密码不正确: " + wrong);
        }
        String locked = postBody("/system/profile/password",
                "{\"oldPassword\":\"Wrong@9ab\",\"newPassword\":\"" + NEW_PASSWORD + "\"}", tokenA);
        assertTrue(locked.contains("失败次数过多"), "第 6 次应命中锁定: " + locked);

        // 改密与吊销链用独立用户 B:正确改密 → 旧令牌(版本 0)下一次请求即 401
        String username = "selfU" + suffix;
        createUser(username, adminToken);
        String token1 = login(username, PASSWORD);
        String profile = getBody("/system/profile", token1, 200);
        assertTrue(profile.contains(q(username)), "档案未含登录名: " + profile);

        String changed = postBody("/system/profile/password",
                "{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}", token1);
        assertEquals(0, jsonCode(changed), "自助改密失败: " + changed);
        assertEquals(401, statusOf("/system/profile", token1), "改密后旧令牌应失效");

        String token2 = login(username, NEW_PASSWORD);
        assertEquals(200, statusOf("/system/profile", token2), "新口令登录后档案应可读");

        // 管理员重置口令 → 用户旧令牌失效
        String reset = mockMvc.perform(put("/system/user/" + userId(adminToken, username) + "/password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + RESET_PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(reset), "重置口令失败: " + reset);
        assertEquals(401, statusOf("/system/profile", token2), "重置后用户旧令牌应失效");

        String token3 = login(username, RESET_PASSWORD);

        // 停用 → 旧令牌失效(版本自增 + 状态判定双保险)
        long uid = userId(adminToken, username);
        String disable = mockMvc.perform(put("/system/user/" + uid + "/status").param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(disable), "停用失败: " + disable);
        assertEquals(401, statusOf("/system/profile", token3), "停用后令牌应失效");

        String enable = mockMvc.perform(put("/system/user/" + uid + "/status").param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(enable), "启用失败: " + enable);
        String token4 = login(username, RESET_PASSWORD);

        // 在线可见 → 强制下线 → 列表消失且令牌失效
        String online = getBody("/system/online/page?pageSize=50", adminToken, 200);
        assertTrue(online.contains(q(username)), "在线列表未见测试用户: " + online);
        String kick = postBody("/system/online/" + uid + "/kick", adminToken);
        assertEquals(0, jsonCode(kick), "强制下线失败: " + kick);
        assertEquals(401, statusOf("/system/profile", token4), "被踢后令牌应失效");
        assertFalse(getBody("/system/online/page?pageSize=50", adminToken, 200).contains(q(username)),
                "被踢后在线列表不应再见该用户");

        // 不能踢自己
        String kickSelf = postBody("/system/online/1/kick", adminToken);
        assertNotEquals(0, jsonCode(kickSelf), "强制下线自己应被拒: " + kickSelf);

        // 强制下线自己的全部会话
        String token5 = login(username, RESET_PASSWORD);
        String selfKick = postBody("/system/profile/force-logout", token5);
        assertEquals(0, jsonCode(selfKick), "自助强退失败: " + selfKick);
        assertEquals(401, statusOf("/system/profile", token5), "自助强退后令牌应失效");
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private long userId(String adminToken, String username) throws Exception {
        String page = mockMvc.perform(get("/system/user/page").param("username", username)
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(extract(Pattern.compile("\"userId\":(\\d+)"), page));
    }

    private void createUser(String username, String adminToken) throws Exception {
        String response = postBody("/system/user", "{\"username\":\"" + username + "\",\"password\":\""
                + PASSWORD + "\",\"nickname\":\"自助安全用户\",\"orgId\":1,\"roleIds\":[]}", adminToken);
        assertEquals(0, jsonCode(response), "建用户失败: " + response);
    }

    private int statusOf(String url, String token) {
        try {
            return mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String getBody(String url, String token, int expectedStatus) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postBody(String url, String body, String token) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postBody(String url, String token) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String login(String username, String password) throws Exception {
        String captchaKey = "it-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            String response = mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                    + "\",\"captchaKey\":\"" + captchaKey + "\",\"captchaCode\":\"ABCD\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(response.contains("\"token\""), "登录失败: " + username + " -> " + response);
            return extract(TOKEN, response);
        } finally {
            redisTemplate.delete(redisKey);
        }
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "响应中未找到 " + pattern + " : " + text);
        return matcher.group(1);
    }

    private static int jsonCode(String text) {
        return Integer.parseInt(extract(Pattern.compile("\"code\":(\\d+)"), text));
    }
}
