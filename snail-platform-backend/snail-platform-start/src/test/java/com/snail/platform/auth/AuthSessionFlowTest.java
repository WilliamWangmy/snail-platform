package com.snail.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 职责:会话吊销全链回归——租户停用/到期、用户登出/停用/删除后,存量令牌下一次请求即 401;
 * 恢复状态不复活旧令牌,必须重新登录;重登后的新令牌不被旧缓存快照误杀。
 * 上游:snail-platform-start 装配后的真实应用上下文(需本机 MySQL/Valkey,经 SNAIL_DB_* 环境变量注入口令);
 * 下游:无断言外设。未设 SNAIL_DB_USERNAME 时整类跳过,不拖累离线构建。
 * 口径:每次运行用纳秒后缀生成互不冲突的租户码/登录名,可重复执行;401 断言取 HTTP 状态,业务拒绝取响应文案。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class AuthSessionFlowTest {

    /** 全链 MVC,走真实安全过滤链与租户拦截器 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void sessionRevocationFlow() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        Long demoTenantId = provisionTenant(suffix, adminToken);
        String tenantToken = login("tadmin" + suffix, "Admin@123");
        assertAuthenticated(tenantToken, "开通租户的管理员新令牌应可用");

        proveTenantDisableRevokesSessions(demoTenantId, suffix, adminToken, tenantToken);
        proveTenantExpiryRevokesSessions(demoTenantId, suffix, adminToken);

        proveUserSessionLifecycle(suffix, adminToken);
    }

    /** 停用即 401;重新启用不复活旧令牌;重登令牌可用(旧缓存快照不误杀新版本) */
    private void proveTenantDisableRevokesSessions(Long tenantId, String suffix, String adminToken,
                                                   String tenantToken) throws Exception {
        String disabled = tenantStatus(tenantId, 1, adminToken);
        assertEquals(0, jsonCode(disabled), "停用演示租户失败: " + disabled);
        assertUnauthorized(tenantToken, "租户停用后存量令牌应 401");
        assertTrue(loginAttempt("tadmin" + suffix, "Admin@123").contains("租户已停用"),
                "停用租户后仍可登录");

        String enabled = tenantStatus(tenantId, 0, adminToken);
        assertEquals(0, jsonCode(enabled), "恢复演示租户失败: " + enabled);
        assertUnauthorized(tenantToken, "租户恢复后旧令牌仍应保持吊销");

        String reLoginToken = login("tadmin" + suffix, "Admin@123");
        assertAuthenticated(reLoginToken, "租户恢复后重登的新令牌应可用");
    }

    /** 到期与停用同口径:存量令牌 401、登录被拒;清空到期时间也不复活旧令牌 */
    private void proveTenantExpiryRevokesSessions(Long tenantId, String suffix, String adminToken)
            throws Exception {
        String validToken = login("tadmin" + suffix, "Admin@123");
        String expiring = updateTenant(tenantId, suffix, "2020-01-01T00:00:00", adminToken);
        assertEquals(0, jsonCode(expiring), "设置租户到期时间失败: " + expiring);
        assertUnauthorized(validToken, "租户到期后存量令牌应 401");
        assertTrue(loginAttempt("tadmin" + suffix, "Admin@123").contains("租户已到期"),
                "到期租户仍可登录");

        String cleared = updateTenant(tenantId, suffix, null, adminToken);
        assertEquals(0, jsonCode(cleared), "清空到期时间失败: " + cleared);
        assertUnauthorized(validToken, "清空到期时间后旧令牌仍应保持吊销");

        String reLoginToken = login("tadmin" + suffix, "Admin@123");
        assertAuthenticated(reLoginToken, "到期解除后重登的新令牌应可用");
    }

    /** 用户维度:登出、停用、启用、删除逐段吊销;每段都验证重登令牌可用 */
    private void proveUserSessionLifecycle(String suffix, String adminToken) throws Exception {
        Long orgId = Long.valueOf(createOrg("会话叶子" + suffix, adminToken));
        String username = "sess" + suffix;
        long userId = createUser(username, orgId, adminToken);

        String userToken = login(username, "User@1234");
        assertAuthenticated(userToken, "新建用户新令牌应可用");

        String loggedOut = postJson("/system/auth/logout", "", userToken);
        assertEquals(0, jsonCode(loggedOut), "登出失败: " + loggedOut);
        assertUnauthorized(userToken, "登出后旧令牌应 401");

        String afterLogout = login(username, "User@1234");
        assertAuthenticated(afterLogout, "登出后重登的新令牌应可用");

        String disabled = userStatus(userId, 1, adminToken);
        assertEquals(0, jsonCode(disabled), "停用用户失败: " + disabled);
        assertUnauthorized(afterLogout, "用户停用后存量令牌应 401");
        assertTrue(loginAttempt(username, "User@1234").contains("账号已停用"), "停用账号仍可登录");

        String enabled = userStatus(userId, 0, adminToken);
        assertEquals(0, jsonCode(enabled), "恢复用户失败: " + enabled);
        assertUnauthorized(afterLogout, "用户恢复后旧令牌仍应保持吊销");
        String afterEnable = login(username, "User@1234");
        assertAuthenticated(afterEnable, "用户恢复后重登的新令牌应可用");

        String deleted = deleteUser(userId, adminToken);
        assertEquals(0, jsonCode(deleted), "删除用户失败: " + deleted);
        assertUnauthorized(afterEnable, "用户删除后存量令牌应 401");
        assertTrue(loginAttempt(username, "User@1234").contains("账号或密码错误"), "删除账号仍可登录");

        String delOrg = mockMvc.perform(delete("/system/org/" + orgId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(delOrg), "清理测试组织失败: " + delOrg);
    }

    private Long provisionTenant(String suffix, String adminToken) throws Exception {
        String body = """
                {"tenantCode":"sess%s","name":"会话演示%s","contactUser":"张三","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":5,"remark":"会话回归开通",
                 "adminUsername":"tadmin%s","adminPassword":"Admin@123"}
                """.formatted(suffix, suffix, suffix);
        String response = postJson("/system/tenant", body, adminToken);
        assertTrue(response.contains("\"code\":0"), "开通租户失败: " + response);
        return Long.valueOf(extract(DATA_ID, response));
    }

    private String tenantStatus(Long tenantId, int status, String adminToken) throws Exception {
        return mockMvc.perform(put("/system/tenant/" + tenantId + "/status").param("status", String.valueOf(status))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String updateTenant(Long tenantId, String suffix, String expireTime, String adminToken)
            throws Exception {
        String expireJson = expireTime == null ? "null" : "\"" + expireTime + "\"";
        String body = "{\"name\":\"会话演示" + suffix + "\",\"contactUser\":\"张三\",\"contactPhone\":\"13900000000\","
                + "\"expireTime\":" + expireJson + ",\"accountLimit\":5,\"remark\":\"会话回归\"}";
        return putJson("/system/tenant/" + tenantId, body, adminToken);
    }

    private String createOrg(String name, String adminToken) throws Exception {
        String response = postJson("/system/org",
                "{\"parentId\":1,\"name\":\"" + name + "\",\"sort\":0}", adminToken);
        assertTrue(response.contains("\"code\":0"), "建组织失败: " + response);
        return extract(DATA_ID, response);
    }

    private long createUser(String username, Long orgId, String adminToken) throws Exception {
        String body = """
                {"username":"%s","password":"User@1234","nickname":"会话测试","email":null,"phone":null,
                 "orgId":%d,"remark":null,"roleIds":[2]}
                """.formatted(username, orgId);
        String response = postJson("/system/user", body, adminToken);
        assertTrue(response.contains("\"code\":0"), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private String userStatus(long userId, int status, String adminToken) throws Exception {
        return mockMvc.perform(put("/system/user/" + userId + "/status").param("status", String.valueOf(status))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String deleteUser(long userId, String adminToken) throws Exception {
        return mockMvc.perform(delete("/system/user/" + userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertAuthenticated(String token, String message) throws Exception {
        String me = mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(me), message + ": " + me);
    }

    private void assertUnauthorized(String token, String message) throws Exception {
        mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String login(String username, String password) throws Exception {
        String response = loginAttempt(username, password);
        assertTrue(response.contains("\"token\""), "登录失败: " + response);
        return extract(TOKEN, response);
    }

    /** 带真实验证码的登录尝试,返回完整响应——成功链取令牌,失败分支断文案都靠它 */
    private String loginAttempt(String username, String password) throws Exception {
        String captchaKey = "it-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            return mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                    + "\",\"captchaKey\":\"" + captchaKey + "\",\"captchaCode\":\"ABCD\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        } finally {
            redisTemplate.delete(redisKey);
        }
    }

    private String postJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String putJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(put(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "响应中未找到 " + pattern + " : " + text);
        return matcher.group(1);
    }

    private static int jsonCode(String text) {
        return Integer.parseInt(extract(CODE, text));
    }
}
