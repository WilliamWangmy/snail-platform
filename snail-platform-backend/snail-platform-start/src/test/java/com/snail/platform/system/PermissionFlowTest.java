package com.snail.platform.system;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 职责:阶段⑦-1 权限全量收口的端到端回归——未认证 401、无授权 403、授权后放行、
 * 角色停用/启用与用户角色清空的即时生效(userPerms 缓存失效实证)、平台面与租户面的边界。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。授菜单端点属 ⑦-2,本类直插 sys_role_menu 准备授权态;每次运行用纳秒后缀可重复执行。
 * 残留:演示租户有意保留——销户不是本回归职责,与阶段③⑤同一口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class PermissionFlowTest {

    /** 全链 MVC:过滤链注入权限码,方法级 @PreAuthorize 判定 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 授权关系直插:⑦-2 前尚无授菜单端点 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void permissionFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        mockMvc.perform(get("/system/user/page"))
                .andExpect(status().isUnauthorized());

        long roleId = createRole(suffix, adminToken);
        String username = "permU" + suffix;
        long userId = createUser(username, roleId, adminToken);
        grantMenu(roleId, 100L);
        String userToken = login(username, "User@1234");

        assertAllowed(userToken, "/system/user/page", "授权后用户列表应放行");
        assertDenied(userToken, "/system/tenant/page", "租户面用户不得进入平台面");

        changeRoleStatus(roleId, 1, adminToken);
        assertDenied(userToken, "/system/user/page", "角色停用后已登录会话应立刻失去权限");
        changeRoleStatus(roleId, 0, adminToken);
        assertAllowed(userToken, "/system/user/page", "角色恢复后权限应重新生效");

        clearRoles(userId, adminToken);
        assertDenied(userToken, "/system/user/page", "清空用户角色后应失去权限");

        assertAllowed(adminToken, "/system/tenant/page", "内置 admin 应可进入平台面");
        proveNewTenantAdminScope(suffix, adminToken);

        deleteUser(userId, adminToken);
        deleteRole(roleId, adminToken);
    }

    /** A 语义:新租户 admin 无任何 sys_role_menu 行,租户面隐式全量;平台面仍须显式授权 */
    private void proveNewTenantAdminScope(String suffix, String adminToken) throws Exception {
        String body = """
                {"tenantCode":"perm%s","name":"权限演示公司%s","contactUser":"张三","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":5,"remark":"权限回归开通",
                 "adminUsername":"permTadmin%s","adminPassword":"Admin@123"}
                """.formatted(suffix, suffix, suffix);
        String created = postJson("/system/tenant", body, adminToken);
        assertTrue(created.contains("\"code\":0"), "开通租户失败: " + created);

        String tenantToken = login("permTadmin" + suffix, "Admin@123");
        assertAllowed(tenantToken, "/system/role/page", "新租户内置 admin 应隐式持有租户面权限");
        assertDenied(tenantToken, "/system/tenant/page", "新租户 admin 不得进入平台面");
    }

    private void assertAllowed(String token, String url, String message) throws Exception {
        String response = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), message + ": " + response);
    }

    private void assertDenied(String token, String url, String message) throws Exception {
        String response = mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(403, jsonCode(response), message + ": " + response);
    }

    private long createRole(String suffix, String adminToken) throws Exception {
        String body = "{\"roleName\":\"权限角色\",\"roleCode\":\"perm" + suffix + "\",\"dataScope\":4,\"sort\":9}";
        String response = postJson("/system/role", body, adminToken);
        assertTrue(response.contains("\"code\":0"), "建角色失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createUser(String username, long roleId, String adminToken) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"User@1234\","
                + "\"nickname\":\"权限测试\",\"orgId\":1,\"roleIds\":[" + roleId + "]}";
        String response = postJson("/system/user", body, adminToken);
        assertTrue(response.contains("\"code\":0"), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void grantMenu(long roleId, long menuId) {
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
    }

    private void changeRoleStatus(long roleId, int statusValue, String adminToken) throws Exception {
        String response = mockMvc.perform(put("/system/role/" + roleId + "/status")
                        .param("status", String.valueOf(statusValue))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "角色状态变更失败: " + response);
    }

    private void clearRoles(long userId, String adminToken) throws Exception {
        String response = mockMvc.perform(put("/system/user/" + userId + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "清空用户角色失败: " + response);
    }

    private void deleteUser(long userId, String adminToken) throws Exception {
        String response = mockMvc.perform(delete("/system/user/" + userId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "删除测试用户失败: " + response);
    }

    private void deleteRole(long roleId, String adminToken) throws Exception {
        String response = mockMvc.perform(delete("/system/role/" + roleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "删除测试角色失败: " + response);
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
    }

    private String postJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(post(url)
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
