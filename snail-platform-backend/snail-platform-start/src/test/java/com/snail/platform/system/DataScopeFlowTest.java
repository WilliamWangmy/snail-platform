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
 * 职责:阶段④数据权限的端到端回归——四档 data_scope 的可见集裁剪、多角色 OR 并集、无角色退回本人、
 * 分页 count 与权限谓词一致、orgSubtree 缓存随组织新增失效、子树清单接口。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。每步用纳秒后缀新建互不冲突的组织/角色/用户,组织每轮全新所以"新组织内"的总数断言可重复执行;
 * 根组织(1 号)会随轮次累积测试用户,对它的断言只用成员存在性不用总数。
 * 权限:自定义角色经 sys_role_menu 直插"用户列表+新增用户"两码(授菜单端点属 ⑦-2),否则权限闸先于数据权限闸;
 * 无角色用户现在停在 403,数据权限的"无角色退回本人"不再有 HTTP 观测面。
 * 残留:测试角色与根组织用户有意保留——销户不是本回归职责,与阶段③流程测试同一口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class DataScopeFlowTest {

    /** 全链 MVC:认证上下文、租户拦截器、数据权限拦截器、分页拦截器全部真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 授权关系直插:⑦-2 前尚无授菜单端点,测试自备 sys_role_menu 行 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern DATA_LIST = Pattern.compile("\"data\":\\[([^\\]]*)]");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern TOTAL = Pattern.compile("\"total\":(\\d+)");

    private static final String PASSWORD = "User@1234";

    @Test
    void dataScopeFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        long orgX = Long.parseLong(createOrg(1L, "范畴X" + suffix, adminToken));
        long orgZ = Long.parseLong(createOrg(orgX, "范畴Z" + suffix, adminToken));

        long r2 = createRole("档二" + suffix, "ds2" + suffix, 2, adminToken);
        long r3 = createRole("档三" + suffix, "ds3" + suffix, 3, adminToken);
        long r4 = createRole("档四" + suffix, "ds4" + suffix, 4, adminToken);

        String u2 = "dsU2" + suffix;
        String u3 = "dsU3" + suffix;
        String u4 = "dsU4" + suffix;
        String u5 = "dsU5" + suffix;
        String u6 = "dsU6" + suffix;
        long uid2 = createUser(u2, orgX, "[" + r2 + "]", adminToken);
        createUser(u3, orgX, "[" + r3 + "]", adminToken);
        long uid4 = createUser(u4, 1L, "[" + r4 + "]", adminToken);
        createUser(u5, orgZ, "[]", adminToken);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);

        String token2 = login(u2, PASSWORD);
        String token3 = login(u3, PASSWORD);
        String token4 = login(u4, PASSWORD);
        String token5 = login(u5, PASSWORD);

        // u6 由 u4 亲手创建:本人臂的唯一活证据,org_id 也落在 X
        long uid6 = createUser(u6, orgX, "[]", token4);

        proveOwnOrgScope(suffix, token2, u2, u3, u6, u4, u5);
        proveSubtreeScope(suffix, token3, u2, u3, u5, u6, u4);
        proveSelfScope(suffix, token4, u6, u2, u4);
        proveNoRoleDenied(token5);
        proveAdminSeesAll(suffix, adminToken, u2, u3, u4, u5, u6);
        proveCountMatchesScope(suffix, token2, u6);

        proveUnionOfRoles(suffix, adminToken, token4, uid4, r4, r2, u2, u4, u6);

        proveSubtreeIdsAndEvict(orgX, orgZ, suffix, adminToken);

        cleanup(adminToken, uid6, uid2, uid4, orgX, orgZ, r2, r3, r4);
    }

    /** 档位二:只看本组织 X——同组织同伴可见,父组织与子组织的行都不可见 */
    private void proveOwnOrgScope(String suffix, String token2, String u2, String u3, String u6,
                                  String u4, String u5) throws Exception {
        String page = userPage(token2, 100);
        assertTrue(page.contains(q(u2)) && page.contains(q(u3)) && page.contains(q(u6)),
                "本组织行应全部可见: " + page);
        assertFalse(page.contains(q(u4)) || page.contains(q(u5)) || page.contains(quoted("admin")),
                "非本组织行泄漏: " + page);
        assertEquals(3, total(page), "档位二总数应为组织X内3人: " + page);
    }

    /** 档位三:本组织及以下——子树 {X,Z} 四人都可见 */
    private void proveSubtreeScope(String suffix, String token3, String u2, String u3, String u5,
                                   String u6, String u4) throws Exception {
        String page = userPage(token3, 100);
        assertTrue(page.contains(q(u2)) && page.contains(q(u3)) && page.contains(q(u5)) && page.contains(q(u6)),
                "子树内行应全部可见: " + page);
        assertFalse(page.contains(q(u4)) || page.contains(quoted("admin")), "子树外行泄漏: " + page);
        assertEquals(4, total(page), "档位三总数应为子树内4人: " + page);
    }

    /** 档位四:仅本人——只有自己做过的行,连自己都看不到(自己由 admin 创建) */
    private void proveSelfScope(String suffix, String token4, String u6, String u2, String u4) throws Exception {
        String page = userPage(token4, 100);
        assertTrue(page.contains(q(u6)), "本人创建的行不可见: " + page);
        assertFalse(page.contains(q(u2)) || page.contains(q(u4)), "非本人创建的行泄漏: " + page);
        assertEquals(1, total(page), "档位四总数应只有 u6: " + page);
    }

    /** 无角色即无权限码:权限闸先于数据权限闸,用户列表直接 403 */
    private void proveNoRoleDenied(String token5) throws Exception {
        String response = mockMvc.perform(get("/system/user/page").param("pageSize", "100")
                        .header("Authorization", "Bearer " + token5))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(403, jsonCode(response), "无角色用户应被权限闸拒绝: " + response);
    }

    /** 档位一(admin)不设限:窗口根本不开,全部测试用户可见 */
    private void proveAdminSeesAll(String suffix, String adminToken, String... usernames) throws Exception {
        String page = userPage(adminToken, 200);
        for (String username : usernames) {
            assertTrue(page.contains(q(username)), "全部档应看到所有人: " + username);
        }
    }

    /** 分页总数与权限谓词一致:count SQL 必须吃到改写后的 WHERE(拦截器顺序实证) */
    private void proveCountMatchesScope(String suffix, String token2, String u6) throws Exception {
        String page = userPage(token2, 1);
        assertEquals(3, total(page), "count 未随权限谓词收敛,分页顺序被破坏: " + page);
        assertTrue(page.contains(q(u6)), "末页前应仍命中权限内数据: " + page);
    }

    /** 多角色 OR 并集:u4 加档二后 org 臂(1 号根)并上本人臂,同 org 用户与自建行都可见 */
    private void proveUnionOfRoles(String suffix, String adminToken, String token4, long uid4,
                                   long r4, long r2, String u2, String u4, String u6) throws Exception {
        String assign = mockMvc.perform(put("/system/user/" + uid4 + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[" + r4 + "," + r2 + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(assign), "并集前置授权失败: " + assign);

        String page = userPage(token4, 200);
        assertTrue(page.contains(q(u4)) && page.contains(q(u6)) && page.contains(quoted("admin")),
                "并集后应见本组织(根)+本人创建: " + page);
        assertFalse(page.contains(q(u2)), "并集不应扩到档位二的同级组织之外: " + page);
    }

    /** 子树清单接口 + orgSubtree 缓存:新增叶子后清单必须立刻出现新 ID(失效点实证) */
    private void proveSubtreeIdsAndEvict(long orgX, long orgZ, String suffix, String adminToken)
            throws Exception {
        String before = mockMvc.perform(get("/system/org/" + orgX + "/subtree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String ids = extract(DATA_LIST, before);
        assertTrue(ids.contains(String.valueOf(orgX)) && ids.contains(String.valueOf(orgZ)),
                "子树清单应含自身与下级: " + before);

        long orgW = Long.parseLong(createOrg(orgX, "范畴W" + suffix, adminToken));
        String after = mockMvc.perform(get("/system/org/" + orgX + "/subtree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(extract(DATA_LIST, after).contains(String.valueOf(orgW)),
                "组织新增后缓存未失效,子树清单缺新叶子: " + after);
    }

    private void cleanup(String adminToken, long uid6, long uid2, long uid4,
                         long orgX, long orgZ, long r2, long r3, long r4) throws Exception {
        // u5/u3 留在原地:注销入口不在本回归职责内;被断言依赖与挡删除的先行清掉
        deleteUser(uid6, adminToken);
        deleteUser(uid2, adminToken);
        deleteUser(uid4, adminToken);
        mockMvc.perform(delete("/system/org/" + orgZ).header("Authorization", "Bearer " + adminToken));
        mockMvc.perform(delete("/system/org/" + orgX).header("Authorization", "Bearer " + adminToken));
        for (long roleId : new long[] {r2, r3, r4}) {
            mockMvc.perform(delete("/system/role/" + roleId).header("Authorization", "Bearer " + adminToken));
            jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ?", roleId);
        }
    }

    private void deleteUser(long userId, String token) throws Exception {
        String response = mockMvc.perform(delete("/system/user/" + userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(response), "删除用户失败: " + response);
    }

    private long createUser(String username, long orgId, String roleIdsJson, String token) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\","
                + "\"nickname\":\"scope测试\",\"orgId\":" + orgId + ",\"roleIds\":" + roleIdsJson + "}";
        String response = postJson("/system/user", body, token);
        assertTrue(response.contains("\"code\":0"), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createRole(String name, String code, int dataScope, String token) throws Exception {
        String body = "{\"roleName\":\"" + name + "\",\"roleCode\":\"" + code + "\",\"dataScope\":" + dataScope + ",\"sort\":9}";
        String response = postJson("/system/role", body, token);
        assertTrue(response.contains("\"code\":0"), "建角色失败: " + response);
        long roleId = Long.parseLong(extract(DATA_ID, response));
        grantMenus(roleId, 100L, 1001L);
        return roleId;
    }

    /** 自定义角色补"用户列表+新增用户"两码,让四档数据权限仍能在 HTTP 面被观测 */
    private void grantMenus(long roleId, long... menuIds) {
        for (long menuId : menuIds) {
            jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
    }

    private String createOrg(long parentId, String name, String token) throws Exception {
        String response = postJson("/system/org",
                "{\"parentId\":" + parentId + ",\"name\":\"" + name + "\",\"sort\":0}", token);
        assertTrue(response.contains("\"code\":0"), "建组织失败: " + response);
        return extract(DATA_ID, response);
    }

    private String userPage(String token, int pageSize) throws Exception {
        return mockMvc.perform(get("/system/user/page").param("pageSize", String.valueOf(pageSize))
                        .header("Authorization", "Bearer " + token))
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

    private String postJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 用户名的 JSON 匹配串:防止裸后缀被子串误命中 */
    private static String q(String username) {
        return quoted(username);
    }

    private static String quoted(String s) {
        return "\"" + s + "\"";
    }

    private static long total(String pageJson) {
        return Long.parseLong(extract(TOTAL, pageJson));
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
