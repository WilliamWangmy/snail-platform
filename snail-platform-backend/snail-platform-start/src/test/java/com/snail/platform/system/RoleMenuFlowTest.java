package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snail.platform.support.TestPasswordFlagSupport;
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
 * 职责:阶段⑦-2 角色菜单授权与当前用户菜单树的端到端回归——授权即时改变权限与导航、
 * 站内/外链与大屏两棵树分流、父目录容器补入、平台面显隐与二次判权、无平台码时的平台授权保留、
 * 删角色清授权行。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。每轮纳秒后缀建角色/用户/临时菜单,末尾删净。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class RoleMenuFlowTest {

    /** 全链 MVC:授权、权限缓存、导航组树真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 直插授权行,准备"无平台码的授权人"场景 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void roleMenuFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(Math.abs(System.nanoTime() % 100_000_000L));
        String adminToken = login("admin", "admin123");

        long dashDir = createMenu(adminToken, rootDirBody("大屏目录" + suffix));
        long dashPage = createMenu(adminToken, "{\"parentId\":" + dashDir + ",\"menuName\":\"大屏页面" + suffix
                + "\",\"menuType\":2,\"target\":2,\"scope\":2,\"path\":\"/d" + suffix
                + "\",\"component\":\"d/page/index\",\"permCode\":\"system:t" + suffix + "dash:view\",\"sort\":1}");
        long external = createMenu(adminToken, "{\"parentId\":1,\"menuName\":\"测试外链" + suffix
                + "\",\"menuType\":2,\"target\":3,\"scope\":2,\"path\":\"https://example.com/" + suffix
                + "\",\"sort\":2}");

        long roleId = createRole("授权角色" + suffix, "grant" + suffix, adminToken);
        String username = "grantU" + suffix;
        long userId = createUser(username, roleId, adminToken);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        String userToken = login(username, "User@1234");

        assertDenied(userToken, "/system/user/page");
        assertTrue(current(userToken, 1).contains("\"data\":[]"),
                "未授权用户的导航应为空");

        assign(roleId, adminToken, "[" + 100 + "," + dashPage + "," + external + "]");
        assertAllowed(userToken, "/system/user/page");

        String adminNav = current(userToken, 1);
        assertTrue(adminNav.contains("系统管理") && adminNav.contains("用户管理")
                        && adminNav.contains("测试外链" + suffix),
                "站内导航缺授权页/外链/父目录容器: " + adminNav);
        assertFalse(adminNav.contains("大屏页面" + suffix) || adminNav.contains("\"menuType\":3"),
                "站内导航混入大屏页或按钮: " + adminNav);

        String dashNav = current(userToken, 2);
        assertTrue(dashNav.contains("大屏目录" + suffix) && dashNav.contains("大屏页面" + suffix),
                "大屏导航缺授权页/父目录容器: " + dashNav);
        assertFalse(dashNav.contains("用户管理") || dashNav.contains("测试外链" + suffix),
                "大屏导航混入站内节点: " + dashNav);

        String adminTree = roleTree(roleId, adminToken);
        assertTrue(adminTree.contains("租户管理"), "平台码持有者应看到平台面节点: " + adminTree);
        assertTrue(Pattern.compile("\"menuId\":100,[^}]*\"checked\":true").matcher(adminTree).find(),
                "已授权节点未勾选: " + adminTree);

        // 无平台码的授权人:平台节点不可见、提交平台菜单即 403、提交租户面时平台授权保留
        long role2 = createRole("受限授权" + suffix, "grantb" + suffix, adminToken);
        String username2 = "grantU2" + suffix;
        createUser(username2, role2, adminToken);
        grantMenuJdbc(role2, 110L);
        grantMenuJdbc(role2, 1104L);
        grantMenuJdbc(role2, 200L);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        String user2Token = login(username2, "User@1234");

        String limitedTree = roleTree(role2, user2Token);
        assertTrue(limitedTree.contains("用户管理") && !limitedTree.contains("租户管理"),
                "无平台码者不应看到平台面节点: " + limitedTree);

        String forbidden = mockMvc.perform(put("/system/role/" + role2 + "/menus")
                        .header("Authorization", "Bearer " + user2Token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"menuIds\":[200]}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(403, jsonCode(forbidden), "无平台码提交平台菜单应 403: " + forbidden);

        assertOk(putJson("/system/role/" + role2 + "/menus", user2Token, "{\"menuIds\":[100]}"),
                "租户面整包授权失败");
        assertEquals(1, countGrants(role2, 200L), "平台面既有授权被租户授权抹掉");
        assertEquals(1, countGrants(role2, 100L), "租户面授权未落库");

        deleteUser(userId, adminToken);
        deleteUser(createUserLookup(username2), adminToken);
        deleteRole(roleId, adminToken);
        deleteRole(role2, adminToken);
        assertEquals(0, countGrants(role2, 200L) + countGrants(role2, 100L),
                "删角色未清授权行");
        assertOk(deleteJson("/system/menu/" + external, adminToken), "清理外链失败");
        assertOk(deleteJson("/system/menu/" + dashPage, adminToken), "清理大屏页失败");
        assertOk(deleteJson("/system/menu/" + dashDir, adminToken), "清理大屏目录失败");
    }

    /** 用户创建接口不回 userId 之外的查询面,清理时按用户名从列表反查 */
    private long createUserLookup(String username) throws Exception {
        String page = mockMvc.perform(get("/system/user/page").param("username", username)
                        .header("Authorization", "Bearer " + login("admin", "admin123")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(extract(Pattern.compile("\"userId\":" + "(\\d+)"), page));
    }

    private String rootDirBody(String name) {
        return "{\"parentId\":0,\"menuName\":\"" + name + "\",\"menuType\":1,\"target\":2,\"scope\":2,\"sort\":90}";
    }

    private long createMenu(String token, String body) throws Exception {
        String response = postJson("/system/menu", token, body);
        assertTrue(response.contains("\"code\":0"), "新增菜单失败: " + body + " -> " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createRole(String name, String code, String token) throws Exception {
        String body = "{\"roleName\":\"" + name + "\",\"roleCode\":\"" + code + "\",\"dataScope\":4,\"sort\":9}";
        String response = postJson("/system/role", token, body);
        assertTrue(response.contains("\"code\":0"), "建角色失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createUser(String username, long roleId, String token) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"User@1234\","
                + "\"nickname\":\"授权测试\",\"orgId\":1,\"roleIds\":[" + roleId + "]}";
        String response = postJson("/system/user", token, body);
        assertTrue(response.contains("\"code\":0"), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void grantMenuJdbc(long roleId, long menuId) {
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
    }

    private int countGrants(long roleId, long menuId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = ?", Integer.class,
                roleId, menuId);
        return count == null ? 0 : count;
    }

    private void assign(long roleId, String token, String menuIdsJson) throws Exception {
        String response = putJson("/system/role/" + roleId + "/menus", token,
                "{\"menuIds\":" + menuIdsJson + "}");
        assertOk(response, "角色授权失败");
    }

    private String current(String token, int target) throws Exception {
        return mockMvc.perform(get("/system/menu/current").param("target", String.valueOf(target))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String roleTree(long roleId, String token) throws Exception {
        return mockMvc.perform(get("/system/role/" + roleId + "/menus")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertAllowed(String token, String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void assertDenied(String token, String url) throws Exception {
        mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    private void deleteUser(long userId, String token) throws Exception {
        String response = deleteJson("/system/user/" + userId, token);
        assertOk(response, "删除测试用户失败");
    }

    private void deleteRole(long roleId, String token) throws Exception {
        String response = deleteJson("/system/role/" + roleId, token);
        assertOk(response, "删除测试角色失败");
    }

    private String deleteJson(String url, String token) throws Exception {
        return mockMvc.perform(delete(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String postJson(String url, String token, String body) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String putJson(String url, String token, String body) throws Exception {
        return mockMvc.perform(put(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertOk(String response, String message) {
        assertEquals(0, jsonCode(response), message + ": " + response);
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
