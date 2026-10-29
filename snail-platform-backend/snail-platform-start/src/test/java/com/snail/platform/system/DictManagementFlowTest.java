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
 * 职责:阶段⑦-3 字典管理的端到端回归——类型/数据 CRUD、重复键拒绝、删类型级联、
 * 字典项按启用状态过滤、dictItem 缓存随写更新、管理面权限码与 items 的登录即可达、
 * 跨租户同名 dictType 不串数据(缓存键含租户的实证)。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。每轮纳秒后缀建类型/数据/角色/用户/租户,租户残留与阶段③⑤同一口径(销户不在回归职责内)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class DictManagementFlowTest {

    /** 全链 MVC:租户拦截器、缓存、权限判定真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 直查级联删除结果与准备无权限用户 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void dictFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(Math.abs(System.nanoTime() % 100_000_000L));
        String dictType = "demo_" + suffix;
        String adminToken = login("admin", "admin123");

        mockMvc.perform(get("/system/dict/type/page"))
                .andExpect(status().isUnauthorized());

        long dictId = createType(dictType, "演示字典" + suffix, adminToken);
        assertRejected(postJson("/system/dict/type", adminToken,
                "{\"dictName\":\"重复类型\",\"dictType\":\"" + dictType + "\"}"), "重复类型应被拒绝");

        createData(dictId, "甲值" + suffix, "a" + suffix, 1, adminToken);
        long dataB = createData(dictId, "乙值" + suffix, "b" + suffix, 2, adminToken);
        assertRejected(postJson("/system/dict/data", adminToken,
                "{\"dictId\":" + dictId + ",\"dictLabel\":\"重复值\",\"dictValue\":\"a" + suffix + "\"}"),
                "同类型重复字典值应被拒绝");

        String items = items(adminToken, dictType);
        assertTrue(items.contains("甲值" + suffix) && items.contains("乙值" + suffix),
                "字典项缺数据: " + items);
        assertTrue(items.indexOf("a" + suffix) < items.indexOf("b" + suffix),
                "字典项未按 sort 排序: " + items);

        assertOk(putJson("/system/dict/data/" + dataB, adminToken,
                "{\"dictLabel\":\"乙改" + suffix + "\",\"sort\":2}"), "改字典标签失败");
        items = items(adminToken, dictType);
        assertTrue(items.contains("乙改" + suffix) && !items.contains("乙值" + suffix),
                "dictItem 缓存未随标签修改失效: " + items);

        assertOk(putJson("/system/dict/data/" + dataB, adminToken,
                "{\"dictLabel\":\"乙改" + suffix + "\",\"sort\":2,\"status\":1}"), "停用字典项失败");
        items = items(adminToken, dictType);
        assertTrue(items.contains("甲值" + suffix) && !items.contains("b" + suffix),
                "停用项仍出现在字典项里: " + items);

        assertOk(putJson("/system/dict/type/" + dictId, adminToken,
                "{\"dictName\":\"字典改名" + suffix + "\"}"), "改字典名称失败");
        assertOk(putJson("/system/dict/type/" + dictId, adminToken,
                "{\"dictName\":\"字典改名" + suffix + "\",\"status\":1}"), "停用字典类型失败");
        assertRejected(items(adminToken, dictType), "停用类型仍可取字典项");
        assertOk(putJson("/system/dict/type/" + dictId, adminToken,
                "{\"dictName\":\"字典改名" + suffix + "\",\"status\":0}"), "启用字典类型失败");
        assertTrue(items(adminToken, dictType).contains("甲值" + suffix),
                "恢复类型后字典项应重新可取");

        String typePage = getJson("/system/dict/type/page?dictType=" + dictType, adminToken);
        assertTrue(typePage.contains("字典改名" + suffix), "类型分页未按标识过滤: " + typePage);
        String activeData = getJson("/system/dict/data/page?dictId=" + dictId + "&status=0", adminToken);
        assertTrue(activeData.contains("\"total\":1"), "按状态过滤字典数据失败: " + activeData);

        provePermissionBoundary(suffix, dictType, adminToken);
        proveTenantIsolation(suffix, dictType, adminToken);

        assertOk(deleteJson("/system/dict/data/" + dataB, adminToken), "删字典数据失败");
        assertOk(deleteJson("/system/dict/type/" + dictId, adminToken), "删字典类型失败");
        assertRejected(getJson("/system/dict/data/page?dictId=" + dictId, adminToken),
                "删类型后其数据页仍可访问");
        Integer liveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_dict_data WHERE dict_id = ? AND deleted = 0",
                Integer.class, dictId);
        assertEquals(0, liveRows == null ? 0 : liveRows, "删类型未级联逻辑删数据");
        assertRejected(postJson("/system/dict/type", adminToken,
                "{\"dictName\":\"复用类型\",\"dictType\":\"" + dictType + "\"}"),
                "已删类型的标识被复用");
    }

    /** 管理端点要 system:dict:list;items 只要求登录,无角色用户也应拿到下拉项 */
    private void provePermissionBoundary(String suffix, String dictType, String adminToken)
            throws Exception {
        long roleId = createRole("字典只读角色", "dictr" + suffix, adminToken);
        String username = "dictU" + suffix;
        long userId = createUser(username, roleId, adminToken);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        String userToken = login(username, "User@1234");

        mockMvc.perform(get("/system/dict/type/page").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        assertTrue(items(userToken, dictType).contains("甲值" + suffix),
                "items 应允许任何已登录用户");

        deleteUser(userId, adminToken);
        deleteRole(roleId, adminToken);
    }

    /** 同名 dictType 在不同租户各存一份:页面互不可见,items 按租户解析且缓存不串 */
    private void proveTenantIsolation(String suffix, String dictType, String adminToken)
            throws Exception {
        String body = """
                {"tenantCode":"dict%s","name":"字典演示公司%s","contactUser":"张三","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":5,"remark":"字典回归开通",
                 "adminUsername":"dictTadmin%s","adminPassword":"Admin@123"}
                """.formatted(suffix, suffix, suffix);
        assertOk(postJson("/system/tenant", adminToken, body), "开通租户失败");
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        String tenantToken = login("dictTadmin" + suffix, "Admin@123");

        long tenantDictId = createType(dictType, "租户二字典" + suffix, tenantToken);
        createData(tenantDictId, "丙值" + suffix, "c" + suffix, 1, tenantToken);

        String tenantItems = items(tenantToken, dictType);
        assertTrue(tenantItems.contains("丙值" + suffix) && !tenantItems.contains("甲值" + suffix),
                "租户二字典项串入其它租户数据: " + tenantItems);
        String mineItems = items(adminToken, dictType);
        assertTrue(mineItems.contains("甲值" + suffix) && !mineItems.contains("丙值" + suffix),
                "租户一字典项串入其它租户数据: " + mineItems);

        String tenantPage = getJson("/system/dict/type/page?dictType=" + dictType, tenantToken);
        assertTrue(tenantPage.contains("租户二字典" + suffix) && !tenantPage.contains("字典改名" + suffix),
                "字典类型分页未按租户隔离: " + tenantPage);

        assertOk(deleteJson("/system/dict/type/" + tenantDictId, tenantToken), "清理租户二字典失败");
    }

    private long createType(String dictType, String name, String token) throws Exception {
        String body = "{\"dictName\":\"" + name + "\",\"dictType\":\"" + dictType + "\",\"remark\":\"回归\"}";
        String response = postJson("/system/dict/type", token, body);
        assertOk(response, "新增字典类型失败");
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createData(long dictId, String label, String value, int sort, String token)
            throws Exception {
        String body = "{\"dictId\":" + dictId + ",\"dictLabel\":\"" + label + "\",\"dictValue\":\""
                + value + "\",\"sort\":" + sort + "}";
        String response = postJson("/system/dict/data", token, body);
        assertOk(response, "新增字典数据失败");
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createRole(String name, String code, String token) throws Exception {
        String body = "{\"roleName\":\"" + name + "\",\"roleCode\":\"" + code + "\",\"dataScope\":4}";
        String response = postJson("/system/role", token, body);
        assertOk(response, "建角色失败");
        return Long.parseLong(extract(DATA_ID, response));
    }

    private long createUser(String username, long roleId, String token) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"User@1234\","
                + "\"nickname\":\"字典测试\",\"orgId\":1,\"roleIds\":[" + roleId + "]}";
        String response = postJson("/system/user", token, body);
        assertOk(response, "建用户失败");
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void deleteUser(long userId, String token) throws Exception {
        assertOk(deleteJson("/system/user/" + userId, token), "删除测试用户失败");
    }

    private void deleteRole(long roleId, String token) throws Exception {
        assertOk(deleteJson("/system/role/" + roleId, token), "删除测试角色失败");
    }

    private String items(String token, String dictType) throws Exception {
        return getJson("/system/dict/items/" + dictType, token);
    }

    private String getJson(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token))
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

    private String deleteJson(String url, String token) throws Exception {
        return mockMvc.perform(delete(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertOk(String response, String message) {
        assertEquals(0, jsonCode(response), message + ": " + response);
    }

    private void assertRejected(String response, String message) {
        assertFalse(response.contains("\"code\":0"), message + ": " + response);
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
