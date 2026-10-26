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
 * 职责:数据权限闸的端到端回归——有限档操作者不能按 ID 读写可见集外的用户、不能把用户挂到可见集外的组织、
 * 不能创建/维护宽于自身档位的角色;内置角色对管理端只读;本人仍可查看自己并自助强制下线。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。每轮用纳秒后缀新建组织/角色/用户,断言用本轮用户名与 ID,可重复执行。
 * 权限:自定义角色经 sys_role_menu 直插所需权限码,避免被权限闸挡在数据权限闸之前。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class DataScopeGuardFlowTest {

    /** 全链 MVC:认证、租户拦截器、数据权限拦截器、分页拦截器全部真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 自定义角色的权限码直插 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    private static final String PASSWORD = "User@1234";

    @Test
    void dataScopeGuardFlow() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        long orgA = Long.parseLong(createOrg(1L, "闸内A" + suffix, adminToken));
        long orgB = Long.parseLong(createOrg(1L, "闸外B" + suffix, adminToken));
        long r2 = createRole("档二守卫" + suffix, "g2" + suffix, 2, adminToken);
        long r4 = createRole("档四守卫" + suffix, "g4" + suffix, 4, adminToken);
        grantMenus(r2, 100L, 1001L, 1002L, 1006L, 1101L, 1102L);
        grantMenus(r4, 100L);

        String u2 = "guard2" + suffix;
        String u4 = "guard4" + suffix;
        String outside = "outside" + suffix;
        long uid2 = createUser(u2, orgA, "[" + r2 + "]", adminToken);
        long uid4 = createUser(u4, orgA, "[" + r4 + "]", adminToken);
        long uidOutside = createUser(outside, orgB, "[]", adminToken);

        String token2 = login(u2, PASSWORD);
        String token4 = login(u4, PASSWORD);

        long uidInside = proveFixedPointWritesAreScoped(token2, orgA, orgB, suffix);
        long narrowerRole = proveRoleCeiling(token2, r2, suffix);
        proveSelfStaysReachable(token4, uid4);
        proveBuiltinRolesReadOnly(adminToken);

        cleanup(adminToken, uid2, uid4, uidOutside, uidInside, orgA, orgB, r2, r4, narrowerRole);
    }

    /** 档二操作者:可见集外的用户读写/踢人全 403;建号与调岗只能落在本组织。返回闸内新建用户 ID */
    private long proveFixedPointWritesAreScoped(String token, long orgA, long orgB, String suffix)
            throws Exception {
        String detail = getJson("/system/user/1", token);
        assertBizCode(detail, 403, "目标用户不在当前数据权限范围内");

        String update = putJson("/system/user/1",
                "{\"nickname\":\"越权改名\",\"email\":null,\"phone\":null,\"orgId\":" + orgA + ",\"remark\":null}",
                token);
        assertBizCode(update, 403, "目标用户不在当前数据权限范围内");

        String kick = postJson("/system/user/1/force-logout", "", token);
        assertBizCode(kick, 403, "目标用户不在当前数据权限范围内");

        String outsideOrg = postJson("/system/user",
                "{\"username\":\"blocked" + suffix + "\",\"password\":\"" + PASSWORD + "\","
                        + "\"nickname\":\"闸外建号\",\"orgId\":" + orgB + ",\"roleIds\":[]}", token);
        assertBizCode(outsideOrg, 403, "目标组织不在当前数据权限范围内");

        String insideOrg = postJson("/system/user",
                "{\"username\":\"inside" + suffix + "\",\"password\":\"" + PASSWORD + "\","
                        + "\"nickname\":\"闸内建号\",\"orgId\":" + orgA + ",\"roleIds\":[]}", token);
        assertEquals(0, jsonCode(insideOrg), "闸内组织建号应放行: " + insideOrg);
        long insideId = Long.parseLong(extract(DATA_ID, insideOrg));

        String moveOut = putJson("/system/user/" + insideId,
                "{\"nickname\":\"闸内建号\",\"email\":null,\"phone\":null,\"orgId\":" + orgB + ",\"remark\":null}",
                token);
        assertBizCode(moveOut, 403, "目标组织不在当前数据权限范围内");

        String stayIn = putJson("/system/user/" + insideId,
                "{\"nickname\":\"闸内建号\",\"email\":null,\"phone\":null,\"orgId\":" + orgA + ",\"remark\":null}",
                token);
        assertEquals(0, jsonCode(stayIn), "闸内组织调岗应放行: " + stayIn);
        return insideId;
    }

    /** 档二操作者:不能新建更宽档角色,也不能把已有角色改宽;收窄方向放行。返回收窄后角色 ID 供清理 */
    private long proveRoleCeiling(String token, long ownRoleId, String suffix) throws Exception {
        String tooBroad = postJson("/system/role",
                "{\"roleName\":\"越权档一\" ,\"roleCode\":\"wide" + suffix + "\",\"dataScope\":1}", token);
        assertBizCode(tooBroad, 403, "不能配置数据档位宽于自身权限的角色");

        String narrower = postJson("/system/role",
                "{\"roleName\":\"收窄档三\",\"roleCode\":\"narrow" + suffix + "\",\"dataScope\":3}", token);
        assertEquals(0, jsonCode(narrower), "不宽于自身的档位应允许: " + narrower);
        long narrowerId = Long.parseLong(extract(DATA_ID, narrower));

        String widen = putJson("/system/role/" + ownRoleId,
                "{\"roleName\":\"档二守卫\",\"dataScope\":1,\"sort\":null,\"status\":null,\"remark\":null}", token);
        assertBizCode(widen, 403, "不能配置数据档位宽于自身权限的角色");

        String narrowOwn = putJson("/system/role/" + narrowerId,
                "{\"roleName\":\"收窄档三\",\"dataScope\":2,\"sort\":null,\"status\":null,\"remark\":null}", token);
        assertEquals(0, jsonCode(narrowOwn), "档位收窄应允许: " + narrowOwn);
        return narrowerId;
    }

    /** 本人臂:仅本人档也能查看自己、自助全设备下线,不被 create_by 口径误伤 */
    private void proveSelfStaysReachable(String token, long userId) throws Exception {
        String selfDetail = getJson("/system/user/" + userId, token);
        assertEquals(0, jsonCode(selfDetail), "查看自己的档案应放行: " + selfDetail);
        assertTrue(selfDetail.contains("\"userId\":" + userId), "详情应回显本人: " + selfDetail);

        String selfLogout = postJson("/system/profile/force-logout", "", token);
        assertEquals(0, jsonCode(selfLogout), "自助强制下线应放行: " + selfLogout);
        mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /** 内置角色对管理端只读:改资料、停用、删除三条写路径一律拒绝 */
    private void proveBuiltinRolesReadOnly(String adminToken) throws Exception {
        String update = putJson("/system/role/1",
                "{\"roleName\":\"被改管理员\",\"dataScope\":1,\"sort\":1,\"status\":0,\"remark\":null}", adminToken);
        assertBizCode(update, 400, "内置角色不可修改");

        String status = putJson("/system/role/1/status?status=1", "", adminToken);
        assertBizCode(status, 400, "内置角色不可修改");

        String removed = deleteJson("/system/role/1", adminToken);
        assertBizCode(removed, 400, "内置角色不可删除");
    }

    private void cleanup(String adminToken, long uid2, long uid4, long uidOutside, long uidInside,
                         long orgA, long orgB, long r2, long r4, long narrowerRole) throws Exception {
        deleteUser(uid2, adminToken);
        deleteUser(uid4, adminToken);
        deleteUser(uidOutside, adminToken);
        deleteUser(uidInside, adminToken);
        deleteRole(r2, adminToken);
        deleteRole(r4, adminToken);
        deleteRole(narrowerRole, adminToken);
        deleteJson("/system/org/" + orgA, adminToken);
        deleteJson("/system/org/" + orgB, adminToken);
    }

    private void deleteUser(long userId, String token) throws Exception {
        String response = deleteJson("/system/user/" + userId, token);
        assertEquals(0, jsonCode(response), "删除用户失败: " + response);
    }

    private void deleteRole(long roleId, String token) throws Exception {
        String response = deleteJson("/system/role/" + roleId, token);
        assertEquals(0, jsonCode(response), "删除角色失败: " + response);
    }

    private String createOrg(long parentId, String name, String token) throws Exception {
        String response = postJson("/system/org",
                "{\"parentId\":" + parentId + ",\"name\":\"" + name + "\",\"sort\":0}", token);
        assertEquals(0, jsonCode(response), "建组织失败: " + response);
        return extract(DATA_ID, response);
    }

    private long createRole(String name, String code, int dataScope, String token) throws Exception {
        String response = postJson("/system/role",
                "{\"roleName\":\"" + name + "\",\"roleCode\":\"" + code + "\",\"dataScope\":" + dataScope + "}",
                token);
        assertEquals(0, jsonCode(response), "建角色失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createUser(String username, long orgId, String roleIdsJson, String token) throws Exception {
        String response = postJson("/system/user",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\","
                        + "\"nickname\":\"守卫测试\",\"orgId\":" + orgId + ",\"roleIds\":" + roleIdsJson + "}",
                token);
        assertEquals(0, jsonCode(response), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void grantMenus(long roleId, long... menuIds) {
        for (long menuId : menuIds) {
            jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", roleId, menuId);
        }
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

    private String getJson(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
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

    private String deleteJson(String url, String token) throws Exception {
        return mockMvc.perform(delete(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void assertBizCode(String response, int expected, String fragment) {
        assertEquals(expected, jsonCode(response), "信封 code 不符: " + response);
        assertTrue(response.contains(fragment), "响应缺文案[" + fragment + "]: " + response);
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
