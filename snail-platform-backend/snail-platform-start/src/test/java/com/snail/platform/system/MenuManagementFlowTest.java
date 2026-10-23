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
 * 职责:阶段⑦-2 菜单目录管理的端到端回归——三元组搭配校验、父子面/展示端约束、内置节点保护、
 * 有下级不可删、目录树缓存随写失效。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。每轮用纳秒后缀建临时目录/页面/按钮/外链,末尾删净。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class MenuManagementFlowTest {

    /** 全链 MVC:平台面权限码与目录服务真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void menuManagementFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        String before = menuTree(adminToken);
        assertTrue(before.contains("系统管理") && before.contains("平台管理"),
                "内置目录缺失: " + before);

        long adminDir = create(adminToken, rootDirBody("测试目录" + suffix, 1));
        long page = create(adminToken, menuBody("测试页面" + suffix, adminDir, 1,
                "system:t" + suffix + ":view", "/t" + suffix + "/page", "t/page/index"));
        long external = create(adminToken, externalBody("测试外链" + suffix, adminDir, suffix));
        long button = create(adminToken, buttonBody("测试按钮" + suffix, page, suffix));

        String after = menuTree(adminToken);
        assertTrue(after.contains("测试目录" + suffix) && after.contains("测试页面" + suffix)
                        && after.contains("测试外链" + suffix) && after.contains("测试按钮" + suffix),
                "新增后目录树未随缓存失效更新: " + after);

        long dashDir = create(adminToken, rootDirBody("大屏目录" + suffix, 2));
        long dashPage = create(adminToken, menuBody("大屏页面" + suffix, dashDir, 2,
                "system:t" + suffix + "dash:view", "/d" + suffix, "d/page/index"));

        assertRejected(postJson("/system/menu", adminToken, externalBody("外链下级" + suffix, external, suffix)),
                "外链不能挂下级");
        assertRejected(postJson("/system/menu", adminToken, parentScopeBody("越面页面" + suffix, adminDir)),
                "归属面");
        assertRejected(postJson("/system/menu", adminToken, crossPlaneBody("越面大屏" + suffix, dashDir)),
                "不能相互嵌套");
        assertRejected(postJson("/system/menu", adminToken, "{"
                        + "\"parentId\":" + page + ",\"menuName\":\"坏按钮\",\"menuType\":3,\"target\":1,"
                        + "\"scope\":2,\"permCode\":\"platform:t:view\"}"),
                "前缀");

        assertOk(putJson("/system/menu/" + page, adminToken,
                "{\"menuName\":\"测试页面改名" + suffix + "\",\"path\":\"/t" + suffix + "/page\","
                        + "\"component\":\"t/page/index\",\"sort\":1,\"visible\":0,\"status\":0,\"remark\":null}"),
                "菜单改名失败");
        String renamed = menuTree(adminToken);
        assertTrue(renamed.contains("测试页面改名" + suffix), "菜单改名未落树: " + renamed);

        assertRejected(putJson("/system/menu/1", adminToken,
                "{\"menuName\":\"系统管理\",\"path\":\"/system\",\"sort\":1,\"visible\":0,\"status\":1}"),
                "内置菜单不可停用");
        assertRejected(deleteJson("/system/menu/1", adminToken), "内置菜单不可删除");
        assertRejected(deleteJson("/system/menu/" + adminDir, adminToken), "存在下级菜单");
        assertRejected(deleteJson("/system/menu/" + page, adminToken), "存在下级菜单");

        assertOk(deleteJson("/system/menu/" + button, adminToken), "删除按钮失败");
        assertOk(deleteJson("/system/menu/" + page, adminToken), "删除页面失败");
        assertOk(deleteJson("/system/menu/" + external, adminToken), "删除外链失败");
        assertOk(deleteJson("/system/menu/" + adminDir, adminToken), "删除目录失败");
        assertOk(deleteJson("/system/menu/" + dashPage, adminToken), "删除大屏页面失败");
        assertOk(deleteJson("/system/menu/" + dashDir, adminToken), "删除大屏目录失败");

        String cleaned = menuTree(adminToken);
        assertFalse(cleaned.contains("测试目录" + suffix), "目录删除后仍在树中: " + cleaned);
    }

    private String rootDirBody(String name, int target) {
        return "{\"parentId\":0,\"menuName\":\"" + name + "\",\"menuType\":1,\"target\":" + target
                + ",\"scope\":2,\"sort\":90}";
    }

    private String menuBody(String name, long parentId, int target, String permCode, String path, String component) {
        return "{\"parentId\":" + parentId + ",\"menuName\":\"" + name + "\",\"menuType\":2,\"target\":" + target
                + ",\"scope\":2,\"path\":\"" + path + "\",\"component\":\"" + component
                + "\",\"permCode\":\"" + permCode + "\",\"sort\":1}";
    }

    private String externalBody(String name, long parentId, String suffix) {
        return "{\"parentId\":" + parentId + ",\"menuName\":\"" + name + "\",\"menuType\":2,\"target\":3,"
                + "\"scope\":2,\"path\":\"https://example.com/" + suffix + "\",\"sort\":2}";
    }

    private String buttonBody(String name, long parentId, String suffix) {
        return "{\"parentId\":" + parentId + ",\"menuName\":\"" + name + "\",\"menuType\":3,\"target\":1,"
                + "\"scope\":2,\"permCode\":\"system:t" + suffix + ":create\",\"sort\":1}";
    }

    private String parentScopeBody(String name, long parentId) {
        return "{\"parentId\":" + parentId + ",\"menuName\":\"" + name + "\",\"menuType\":2,\"target\":1,"
                + "\"scope\":1,\"path\":\"/x\",\"component\":\"x/index\",\"permCode\":\"platform:t:view\"}";
    }

    private String crossPlaneBody(String name, long parentId) {
        return "{\"parentId\":" + parentId + ",\"menuName\":\"" + name + "\",\"menuType\":2,\"target\":1,"
                + "\"scope\":2,\"path\":\"/x\",\"component\":\"x/index\",\"permCode\":\"system:x:view\"}";
    }

    private long create(String token, String body) throws Exception {
        String response = postJson("/system/menu", token, body);
        assertTrue(response.contains("\"code\":0"), "新增菜单失败: " + body + " -> " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private String menuTree(String token) throws Exception {
        return mockMvc.perform(get("/system/menu/tree").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void assertRejected(String response, String needle) {
        assertEquals(400, jsonCode(response), "应回 400 信封: " + response);
        if (needle != null) {
            assertTrue(response.contains(needle), "拒绝文案未带口径关键字: " + response);
        }
    }

    private void assertOk(String response, String message) {
        assertEquals(0, jsonCode(response), message + ": " + response);
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
