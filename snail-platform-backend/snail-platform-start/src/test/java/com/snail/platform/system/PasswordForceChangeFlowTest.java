package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 职责:密码强度与首登强制改密全链回归——口径七反例(AC-006)、黑名单全串精确相等(AC-007)、
 * 建号/开通租户生而待改密(AC-001/002)、自助改密与重置的标记翻转(AC-003)、
 * 强制改密端点的同密码拒绝(AC-008)、兜底闸 403(AC-010)与改密后旧令牌吊销(AC-012)。
 * 上游:snail-platform-start 装配后的真实应用上下文(需本机 MySQL/Valkey,经 SNAIL_DB_* 环境变量注入口令);
 * 下游:无断言外设。未设 SNAIL_DB_USERNAME 时整类跳过,不拖累离线构建。
 * 口径:种子 admin 的标记由 TestPasswordFlagSupport 启动期垫掉;本类新建的账号不参与自愈,
 * 待改密与标记翻转正是本类的断言对象。每次运行用纳秒后缀生成互不冲突的登录名,可重复执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class PasswordForceChangeFlowTest {

    /** 全链 MVC,走真实安全过滤链与兜底过滤器 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");
    private static final Pattern FLAG = Pattern.compile("\"passwordChangeRequired\":(true|false)");

    /** 文案集冻结稿(§5.4),断言逐字比对 */
    private static final String MSG_A = "密码长度需 8-20 位";
    private static final String MSG_B = "密码必须包含大写字母、小写字母、数字和特殊符号";
    private static final String MSG_C = "密码不能包含空格等空白字符";
    private static final String MSG_D = "密码过于常见，请更换";
    private static final String MSG_E = "新密码不能与当前密码相同";

    @Test
    void passwordPolicyAndForceChangeFlow() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        provePolicyMessages(suffix, adminToken);
        Long tenantId = proveProvisionAndGate(suffix, adminToken);
        proveSelfChangeAndReset(suffix, adminToken);
        cleanup(suffix, tenantId, adminToken);
    }

    /** AC-006/007:七反例各回各的文案,黑名单全串精确相等拒、近似值过 */
    private void provePolicyMessages(String suffix, String adminToken) throws Exception {
        String[][] rejects = {
                {"Ab1!", MSG_A},
                {"Ab1!Ab1!Ab1!Ab1!Ab1!A", MSG_A},
                {"abcd1234!x", MSG_B},
                {"ABCD1234!X", MSG_B},
                {"Abcdefgh!x", MSG_B},
                {"Abcdefg123", MSG_B},
                {"Ab1! Ab1!", MSG_C},
                {"P@ssw0rd", MSG_D},
        };
        for (String[] reject : rejects) {
            String response = createUserAttempt("weak" + suffix, reject[0], adminToken);
            assertTrue(response.contains(reject[1]),
                    "密码 " + reject[0] + " 应拒且文案为「" + reject[1] + "」: " + response);
        }
        // AC-001 + AC-007 反例:合规密码创建成功;P@ssw0rd123 不与黑名单全串相等,放行
        String accepted = createUserAttempt("ok" + suffix, "P@ssw0rd123", adminToken);
        assertEquals(0, jsonCode(accepted), "合规密码建号应成功: " + accepted);
    }

    /** AC-002/010/008/012:开通租户的管理员生而待改密,兜底闸 403,改密后旧令牌吊销 */
    private Long proveProvisionAndGate(String suffix, String adminToken) throws Exception {
        String body = """
                {"tenantCode":"pwd%s","name":"改密演示%s","contactUser":"张三","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":5,"remark":"密码回归开通",
                 "adminUsername":"padmin%s","adminPassword":"Admin@123"}
                """.formatted(suffix, suffix, suffix);
        String provisioned = postJson("/system/tenant", body, adminToken);
        assertEquals(0, jsonCode(provisioned), "开通租户失败: " + provisioned);

        String loginResponse = loginAttempt("padmin" + suffix, "Admin@123");
        assertTrue(Boolean.parseBoolean(extract(FLAG, loginResponse)),
                "开通租户的管理员首登应带待改密标记: " + loginResponse);
        String flaggedToken = extract(TOKEN, loginResponse);

        // 兜底闸:业务接口 403 且指明先改密;放行集内的 /me 正常
        String blocked = mockMvc.perform(get("/system/user/page").header("Authorization", "Bearer " + flaggedToken))
                .andExpect(result -> assertEquals(403, result.getResponse().getStatus(),
                        "待改密会话调业务接口应 403"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(blocked.contains("请先完成强制改密"), "403 响应应指明先完成强制改密: " + blocked);
        String me = mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + flaggedToken))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus(), "放行集 /me 不应被兜底闸拦截"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(Boolean.parseBoolean(extract(FLAG, me)), "/me 应回显待改密标记: " + me);

        // AC-008:新密码与当前密码相同,文案 E,标记不变
        String samePassword = postJson("/system/profile/password/force-change",
                "{\"newPassword\":\"Admin@123\"}", flaggedToken);
        assertTrue(samePassword.contains(MSG_E), "同密码应拒且文案 E: " + samePassword);

        // 强制改密成功后当前会话一并失效:旧令牌调业务接口 401(AC-012)
        String changed = postJson("/system/profile/password/force-change",
                "{\"newPassword\":\"New@12345\"}", flaggedToken);
        assertEquals(0, jsonCode(changed), "强制改密应成功: " + changed);
        mockMvc.perform(get("/system/user/page").header("Authorization", "Bearer " + flaggedToken))
                .andExpect(result -> assertEquals(401, result.getResponse().getStatus(),
                        "改密后旧令牌应已被吊销"));

        // 改密后重登:标记清除,业务接口恢复可用;再调强制改密端点应被服务层 403 拒
        String reLogin = loginAttempt("padmin" + suffix, "New@12345");
        assertFalse(Boolean.parseBoolean(extract(FLAG, reLogin)), "改密后重登不应再带标记: " + reLogin);
        String clearToken = extract(TOKEN, reLogin);
        String page = mockMvc.perform(get("/system/user/page").header("Authorization", "Bearer " + clearToken))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus(), "改密后业务接口应恢复可用"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(page), "业务接口应正常返回: " + page);
        String gateAgain = postJson("/system/profile/password/force-change",
                "{\"newPassword\":\"New@99999\"}", clearToken);
        assertTrue(gateAgain.contains("\"code\":403"), "非标记用户直调强制改密应 403: " + gateAgain);
        return Long.valueOf(extract(DATA_ID, provisioned));
    }

    /** AC-003:自助改密成功(记当前时间,标记不再出现);管理员重置后标记回归 */
    private void proveSelfChangeAndReset(String suffix, String adminToken) throws Exception {
        Long orgId = Long.valueOf(postJsonForData("/system/org",
                "{\"parentId\":1,\"name\":\"改密叶子" + suffix + "\",\"sort\":0}", adminToken));
        String username = "pwduser" + suffix;
        String created = postJson("/system/user", """
                {"username":"%s","password":"User@1234","nickname":"改密测试","email":null,"phone":null,
                 "orgId":%d,"remark":null,"roleIds":[]}
                """.formatted(username, orgId), adminToken);
        assertEquals(0, jsonCode(created), "建号失败: " + created);
        long userId = Long.parseLong(extract(DATA_ID, created));

        // 生而待改密:先强制改密一次,才具备自助改密的前置状态
        String flaggedToken = extract(TOKEN, loginAttempt(username, "User@1234"));
        String forced = postJson("/system/profile/password/force-change",
                "{\"newPassword\":\"Self@12345\"}", flaggedToken);
        assertEquals(0, jsonCode(forced), "强制改密应成功: " + forced);

        // AC-003 自助改密:原密码 + 合规新密码,成功
        String selfToken = extract(TOKEN, loginAttempt(username, "Self@12345"));
        String selfChanged = postJson("/system/profile/password",
                "{\"oldPassword\":\"Self@12345\",\"newPassword\":\"Self@67890\"}", selfToken);
        assertEquals(0, jsonCode(selfChanged), "自助改密应成功: " + selfChanged);
        assertTrue(loginAttempt(username, "Self@12345").contains("账号或密码错误"), "自助改密后旧密码应失效");
        String newToken = extract(TOKEN, loginAttempt(username, "Self@67890"));
        String flag = mockMvc.perform(get("/system/auth/me").header("Authorization", "Bearer " + newToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(Boolean.parseBoolean(extract(FLAG, flag)), "自助改密后不应再带待改密标记: " + flag);

        // AC-003 重置:管理员重置后标记回归(置 NULL)
        String reset = putJson("/system/user/" + userId + "/password",
                "{\"password\":\"Reset@1234\"}", adminToken);
        assertEquals(0, jsonCode(reset), "重置密码应成功: " + reset);
        String afterReset = loginAttempt(username, "Reset@1234");
        assertTrue(Boolean.parseBoolean(extract(FLAG, afterReset)), "被重置账号应回到待改密态: " + afterReset);
    }

    private void cleanup(String suffix, Long tenantId, String adminToken) throws Exception {
        // 测试租户停用即可令牌全吊销,行保留供排查;本类自建的组织/用户随租户域,不逐个清
        putJson("/system/tenant/" + tenantId + "/status?status=1", "", adminToken);
    }

    private Long postJsonForData(String url, String body, String token) throws Exception {
        String response = postJson(url, body, token);
        assertEquals(0, jsonCode(response), "请求失败: " + response);
        return Long.valueOf(extract(DATA_ID, response));
    }

    private String createUserAttempt(String username, String password, String adminToken) throws Exception {
        return postJson("/system/user", """
                {"username":"%s","password":"%s","nickname":"密码回归","email":null,"phone":null,
                 "orgId":1,"remark":null,"roleIds":[]}
                """.formatted(username, password), adminToken);
    }

    private String login(String username, String password) throws Exception {
        String response = loginAttempt(username, password);
        assertTrue(response.contains("\"token\""), "登录失败: " + response);
        return extract(TOKEN, response);
    }

    private String loginAttempt(String username, String password) throws Exception {
        String captchaKey = "it-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            return mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                    + "\",\"captchaKey\":\"" + captchaKey + "\",\"captchaCode\":\"ABCD\"}"))
                    .andExpect(result -> assertEquals(200, result.getResponse().getStatus()))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        } finally {
            redisTemplate.delete(redisKey);
        }
    }

    private String postJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String putJson(String url, String body, String token) throws Exception {
        return mockMvc.perform(put(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(result -> assertEquals(200, result.getResponse().getStatus()))
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
