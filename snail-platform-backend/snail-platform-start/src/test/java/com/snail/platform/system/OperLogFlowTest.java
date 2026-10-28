package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.snail.platform.framework.log.SensitiveDataMasker;
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
 * 职责:阶段⑤操作日志与导出的端到端回归——@OperLog 切面留痕(成功/失败分支)、入参脱敏、
 * 详情全文、租户钉死与平台跨租户、xlsx 导出、导出自身留痕(business_type=4)、登录日志查询。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。切面是异步单线程落库,断言用"ID 水位轮询":基线=操作前列表首行 ID,
 * 等新行 ID 冒出来再取证,不等时长写死。每次运行用纳秒后缀,可重复执行。
 * 残留:测试用户与租户有意保留——销户不是本回归职责,与阶段③④同一口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class OperLogFlowTest {

    /** 全链 MVC:认证、租户、数据权限、操作日志切面全部真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** V7 后新建账号生而待改密,演示租户管理员登录前垫掉标记 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern FIRST_ROW_ID = Pattern.compile("\"rows\":\\[\\{\"id\":(\\d+)");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    /** 异步落库轮询:最多 100 次 × 50ms = 5 秒 */
    private static final int POLL_TIMES = 100;
    private static final long POLL_MILLIS = 50L;

    @Test
    void operLogFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        long baseline = currentTopOperLogId(adminToken);
        long scratchUserId = createUser("olScratch" + suffix, adminToken);
        long successRow = waitForNewOperLogRow(adminToken, baseline);
        proveSuccessTrace(successRow, suffix, adminToken);

        long failureRow = exerciseRejectedCreate("olScratch" + suffix, adminToken, successRow);
        proveFailureTrace(failureRow, adminToken);

        long demoTenantId = provisionTenant(suffix, adminToken);
        assertTrue(demoTenantId > 0);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        String demoToken = login("olTadmin" + suffix, "Admin@123");
        exerciseDemoTenantWrite(suffix, demoToken);
        proveTenantPinAndPlatformCross(suffix, adminToken, demoToken);

        proveOperLogExport(adminToken);
        proveLoginLogQueries(suffix, adminToken, demoToken);

        deleteUser(scratchUserId, adminToken);
    }

    /** 成功链取证:脱敏入参、快照字段、耗时与链路 ID 齐备(param 是字符串列,内部 JSON 带转义,断言取宽松子串) */
    private void proveSuccessTrace(long rowId, String suffix, String adminToken) throws Exception {
        String detail = getOperDetail(rowId, adminToken);
        assertTrue(detail.contains("olScratch" + suffix), "param 未含本次建号入参: " + detail);
        assertTrue(detail.contains(SensitiveDataMasker.MASK), "password 未脱敏: " + detail);
        assertFalse(detail.contains("User@1234"), "明文口令泄漏进日志: " + detail);
        assertTrue(detail.contains("\"status\":1"), "成功链应记 status=1: " + detail);
        assertTrue(detail.contains("\"operator\":\"admin\""), "操作人快照缺失: " + detail);
        assertTrue(detail.contains("\"businessType\":1"), "业务类型应为新增: " + detail);
        assertTrue(detail.contains("\"orgId\":1"), "操作人组织快照缺失: " + detail);
        assertFalse(detail.contains("\"traceId\":null"), "traceId 未关联: " + detail);
        assertTrue(detail.contains("\"costTime\":"), "耗时字段缺失: " + detail);
    }

    /** 拒绝分支也要留痕:业务异常(200 信封)同样走切面失败路径 */
    private long exerciseRejectedCreate(String username, String adminToken, long baseline) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"User@1234\","
                + "\"nickname\":\"重复建号\",\"orgId\":1,\"roleIds\":[]}";
        String rejected = postJson("/system/user", body, adminToken);
        assertTrue(rejected.contains("登录名已存在"), "重复建号未被拒: " + rejected);
        return waitForNewOperLogRow(adminToken, baseline);
    }

    private void proveFailureTrace(long rowId, String adminToken) throws Exception {
        String detail = getOperDetail(rowId, adminToken);
        assertTrue(detail.contains("\"status\":0"), "失败链应记 status=0: " + detail);
        assertTrue(detail.contains("登录名已存在"), "errorMsg 未落: " + detail);
    }

    private long provisionTenant(String suffix, String adminToken) throws Exception {
        String body = """
                {"tenantCode":"oldemo%s","name":"日志演示公司%s","contactUser":"张三","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":5,"remark":null,
                 "adminUsername":"olTadmin%s","adminPassword":"Admin@123"}
                """.formatted(suffix, suffix, suffix);
        String response = postJson("/system/tenant", body, adminToken);
        assertTrue(response.contains("\"code\":0"), "开通租户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    /** 演示租户管理员建组织:产生一条只属该租户的留痕 */
    private void exerciseDemoTenantWrite(String suffix, String demoToken) throws Exception {
        String tree = mockMvc.perform(get("/system/org/tree")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long rootOrgId = Long.parseLong(extract(Pattern.compile("\"orgId\":(\\d+)"), tree));
        String response = postJson("/system/org",
                "{\"parentId\":" + rootOrgId + ",\"name\":\"日志叶子" + suffix + "\",\"sort\":0}", demoToken);
        assertTrue(response.contains("\"code\":0"), "演示租户建组织失败: " + response);
    }

    /** 钉死与跨租户:租户管理员只见自己 operator 的行;平台默认可见全量,按租户筛即收窄 */
    private void proveTenantPinAndPlatformCross(String suffix, String adminToken, String demoToken)
            throws Exception {
        String ownOperator = "olTadmin" + suffix;
        String demoPage = pollUntilContains(demoToken, "/system/log/oper/page?pageSize=200",
                "\"operator\":\"" + ownOperator + "\"", "演示租户管理员自己的留痕未及时出现");
        assertFalse(demoPage.contains("\"operator\":\"admin\""),
                "钉死失效,租户管理员看到了平台侧行: " + demoPage);

        pollUntilContains(adminToken, "/system/log/oper/page?pageSize=200",
                "\"operator\":\"" + ownOperator + "\"", "平台侧跨租户视图缺新租户留痕");

        String pinned = mockMvc.perform(get("/system/log/oper/page")
                        .param("pageSize", "200").param("tenantId", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(pinned.contains("\"operator\":\"" + ownOperator + "\""),
                "平台侧按租户 1 筛选后仍混入他租户行: " + pinned);
    }

    /** 导出:xlsx 魔数落响应,导出自身随后必须带 businessType=4 的新留痕(基线在触发前就地取) */
    private void proveOperLogExport(String adminToken) throws Exception {
        long baseline = currentTopOperLogId(adminToken);
        var outcome = mockMvc.perform(get("/system/log/oper/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] bytes = outcome.getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K',
                "导出件不是 xlsx(zip)流, 长度=" + bytes.length);

        long exportRow = waitForNewOperLogRow(adminToken, baseline);
        String list = mockMvc.perform(get("/system/log/oper/page").param("pageSize", "5")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(list.contains("\"id\":" + exportRow + ","), "导出留痕不在列表首位: " + list);
        assertTrue(list.contains("\"businessType\":4"), "导出应记 businessType=4: " + list);

        var loginExport = mockMvc.perform(get("/system/log/login/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        byte[] loginBytes = loginExport.getResponse().getContentAsByteArray();
        assertTrue(loginBytes.length > 4 && loginBytes[0] == 'P' && loginBytes[1] == 'K',
                "登录日志导出不是 xlsx 流");
    }

    /** 登录日志:成功行按用户名可筛;用户不存在的失败行 tenant_id=0,只有平台侧可见 */
    private void proveLoginLogQueries(String suffix, String adminToken, String demoToken) throws Exception {
        String ghost = "olGhost" + suffix;
        String failed = loginAttempt(ghost, "Wrong@12345");
        assertTrue(failed.contains("账号或密码错误"), "幽灵登录应回统一文案: " + failed);

        String ghostPage = mockMvc.perform(get("/system/log/login/page")
                        .param("username", ghost)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(ghostPage.contains("\"username\":\"" + ghost + "\"") && ghostPage.contains("\"status\":0"),
                "归属不明的失败登录未记 tenant_id=0 行: " + ghostPage);

        String demoPage = mockMvc.perform(get("/system/log/login/page")
                        .param("username", ghost)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(demoPage.contains("\"username\":\"" + ghost + "\""),
                "租户钉死失效,演示租户看到了 tenant_id=0 的平台留痕: " + demoPage);

        String ownLogin = mockMvc.perform(get("/system/log/login/page")
                        .param("username", "olTadmin" + suffix)
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(ownLogin.contains("\"status\":1"), "演示租户管理员的成功登录未可筛: " + ownLogin);
    }

    // ------------------------------------------------------------------ 轮询与取证辅助

    private long currentTopOperLogId(String token) throws Exception {
        String page = operPage(token, "pageSize=5");
        Matcher matcher = FIRST_ROW_ID.matcher(page);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    /** 异步落库的水位线等待:等列表首行 ID 越过基线,返回新行 ID */
    private long waitForNewOperLogRow(String token, long baseline) throws Exception {
        for (int i = 0; i < POLL_TIMES; i++) {
            long top = currentTopOperLogId(token);
            if (top > baseline) {
                return top;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return fail("等待操作日志落库超时(基线 " + baseline + ")");
    }

    private String pollUntilContains(String token, String urlWithQuery, String needle, String message)
            throws Exception {
        String page = null;
        for (int i = 0; i < POLL_TIMES; i++) {
            page = mockMvc.perform(get(urlWithQuery)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (page.contains(needle)) {
                return page;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return fail(message + ",最后页面: " + page);
    }

    private String operPage(String token, String query) throws Exception {
        return mockMvc.perform(get("/system/log/oper/page?" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String getOperDetail(long id, String token) throws Exception {
        return mockMvc.perform(get("/system/log/oper/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long createUser(String username, String token) throws Exception {
        String body = "{\"username\":\"" + username + "\",\"password\":\"User@1234\","
                + "\"nickname\":\"日志测试\",\"orgId\":1,\"roleIds\":[]}";
        String response = postJson("/system/user", body, token);
        assertTrue(response.contains("\"code\":0"), "建用户失败: " + response);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private void deleteUser(long userId, String token) throws Exception {
        String response = mockMvc.perform(delete("/system/user/" + userId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, Integer.parseInt(extract(CODE, response)), "清理测试用户失败: " + response);
    }

    private String login(String username, String password) throws Exception {
        String response = loginAttempt(username, password);
        assertTrue(response.contains("\"token\""), "登录失败: " + username + " -> " + response);
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

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "响应中未找到 " + pattern + " : " + text);
        return matcher.group(1);
    }
}
