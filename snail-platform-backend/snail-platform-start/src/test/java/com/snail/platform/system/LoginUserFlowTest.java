package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 职责:当前登录人富快照的端到端回归——/me 一次取齐身份/租户名/组织名/生效角色,
 * 以及 userSession 缓存随昵称、角色、组织、租户改名即时失效(同一令牌连续请求即见新值)。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。演示租户有意保留,与阶段③⑤、权限回归同一口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class LoginUserFlowTest {

    /** 全链 MVC:过滤器装配登录人快照,controller 直接读出参 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");
    private static final Pattern USER_ID = Pattern.compile("\"userId\":(\\d+)");
    private static final Pattern TENANT_ID = Pattern.compile("\"tenantId\":(\\d+)");
    private static final Pattern ORG_ID = Pattern.compile("\"orgId\":(\\d+)");
    private static final Pattern ROLE_ID = Pattern.compile("\"roleId\":(\\d+)");

    @Test
    void loginUserSnapshotEndToEnd() throws Exception {
        String suffix = String.valueOf(Math.abs(System.nanoTime() % 100_000_000L));
        String platformToken = login("admin", "admin123");

        long tenantId = provisionTenant(suffix, platformToken);
        String username = "sessadmin" + suffix;
        String tenantToken = login(username, "Admin@1234");

        String me = getMe(tenantToken);
        long userId = Long.parseLong(extract(USER_ID, me));
        long orgId = Long.parseLong(extract(ORG_ID, me));
        long adminRoleId = Long.parseLong(extract(ROLE_ID, me));
        assertTrue(me.contains("\"username\":\"" + username + "\""), "缺登录名: " + me);
        assertTrue(me.contains("\"nickname\":\"租户管理员\""), "缺显示名: " + me);
        assertTrue(me.contains("\"tenantName\":\"会话演示公司" + suffix + "\""), "缺租户名: " + me);
        assertTrue(me.contains("\"orgName\":\"会话演示公司" + suffix + "\""), "缺组织名: " + me);
        assertTrue(me.contains("\"roleCode\":\"admin\""), "缺生效角色: " + me);
        assertTrue(me.contains("\"roleName\":\"超级管理员\""), "缺角色名: " + me);
        assertTrue(me.contains("\"system:user:list\""), "租户 admin 未拿到隐式租户面权限: " + me);
        assertFalse(me.contains("\"platform:menu:list\""), "租户 admin 不应拿到平台面权限: " + me);
        assertTrue(getMe(platformToken).contains("\"platform:role:assign-menu\""),
                "平台 admin 缺平台授权码(V6 种子未生效)");

        String newNickname = "会话昵称" + suffix;
        updateOwnProfile(userId, orgId, newNickname, tenantToken);
        assertTrue(getMe(tenantToken).contains("\"nickname\":\"" + newNickname + "\""),
                "改昵称后 userSession 未失效");

        long extraRoleId = createRole(suffix, tenantToken);
        assignRoles(userId, "[" + adminRoleId + "," + extraRoleId + "]", tenantToken);
        String withTwoRoles = getMe(tenantToken);
        assertTrue(withTwoRoles.contains("\"roleCode\":\"sess" + suffix + "\""),
                "分配角色后 userSession 未失效: " + withTwoRoles);
        assertTrue(withTwoRoles.indexOf("\"roleCode\":\"admin\"")
                < withTwoRoles.indexOf("\"roleCode\":\"sess" + suffix + "\""), "角色未按显示顺序排列");

        String newRoleName = "会话角色改名" + suffix;
        updateRole(extraRoleId, newRoleName, tenantToken);
        assertTrue(getMe(tenantToken).contains("\"roleName\":\"" + newRoleName + "\""),
                "角色改名后 userSession 未失效");

        assignRoles(userId, "[" + adminRoleId + "]", tenantToken);
        assertFalse(getMe(tenantToken).contains("\"roleCode\":\"sess" + suffix + "\""),
                "收回角色后 userSession 未失效");

        updateOrg(orgId, "会话组织改名" + suffix, tenantToken);
        assertTrue(getMe(tenantToken).contains("\"orgName\":\"会话组织改名" + suffix + "\""),
                "组织改名后 userSession 未失效");

        updateTenant(tenantId, "会话租户改名" + suffix, platformToken);
        assertTrue(getMe(tenantToken).contains("\"tenantName\":\"会话租户改名" + suffix + "\""),
                "租户改名后 userSession 未失效");

        deleteRole(extraRoleId, tenantToken);
    }

    /** 开通演示租户:根组织与内置 admin 角色由模板复制,新租户管理员即被测对象 */
    private long provisionTenant(String suffix, String platformToken) throws Exception {
        String body = """
                {"tenantCode":"sess%s","name":"会话演示公司%s","contactUser":"李四","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":10,"remark":"登录人快照回归开通",
                 "adminUsername":"sessadmin%s","adminPassword":"Admin@1234"}
                """.formatted(suffix, suffix, suffix);
        String response = postJson("/system/tenant", body, platformToken);
        assertTrue(response.contains("\"code\":0"), "开通租户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void updateOwnProfile(long userId, long orgId, String nickname, String token) throws Exception {
        String body = "{\"nickname\":\"" + nickname + "\",\"orgId\":" + orgId + "}";
        String response = putJson("/system/user/" + userId, body, token);
        assertEquals(0, jsonCode(response), "改昵称失败: " + response);
    }

    private long createRole(String suffix, String token) throws Exception {
        String body = "{\"roleName\":\"会话角色\",\"roleCode\":\"sess" + suffix + "\",\"dataScope\":4,\"sort\":9}";
        String response = postJson("/system/role", body, token);
        assertTrue(response.contains("\"code\":0"), "建角色失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void assignRoles(long userId, String roleIdsJson, String token) throws Exception {
        String response = putJson("/system/user/" + userId + "/roles",
                "{\"roleIds\":" + roleIdsJson + "}", token);
        assertEquals(0, jsonCode(response), "分配角色失败: " + response);
    }

    private void updateRole(long roleId, String roleName, String token) throws Exception {
        String response = putJson("/system/role/" + roleId, "{\"roleName\":\"" + roleName + "\"}", token);
        assertEquals(0, jsonCode(response), "改角色失败: " + response);
    }

    private void updateOrg(long orgId, String name, String token) throws Exception {
        String response = putJson("/system/org/" + orgId, "{\"name\":\"" + name + "\"}", token);
        assertEquals(0, jsonCode(response), "改组织失败: " + response);
    }

    private void updateTenant(long tenantId, String name, String token) throws Exception {
        String response = putJson("/system/tenant/" + tenantId, "{\"name\":\"" + name + "\"}", token);
        assertEquals(0, jsonCode(response), "改租户失败: " + response);
    }

    private void deleteRole(long roleId, String token) throws Exception {
        String response = mockMvc.perform(delete("/system/role/" + roleId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "删除测试角色失败: " + response);
    }

    private String getMe(String token) throws Exception {
        String response = mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "/me 失败: " + response);
        return response;
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

    private String login(String username, String password) throws Exception {
        String captchaKey = "it-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            String response = mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                    + "\",\"captchaKey\":\"" + captchaKey + "\",\"captchaCode\":\"ABCD\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(response.contains("\"token\""), "登录失败: " + username + " -> " + response);
            return extract(TOKEN, response);
        } finally {
            redisTemplate.delete(redisKey);
        }
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
