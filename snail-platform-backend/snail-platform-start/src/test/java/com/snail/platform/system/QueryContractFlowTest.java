package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 职责:分页契约的 HTTP 端回归——XxxQuery 绑定口径(默认值、上限、枚举、时间格式)与拒绝信封。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 未设则整类跳过);
 * 下游:约定 §7 的分页纪律(≤200、pageNum 从 1 起、beginTime ISO 格式)。
 * 口径:绑定缺省走紧凑构造器(无参即 1/10、日志域 1/20),拒绝一律 HTTP 200 + code=400 信封,
 * 与业务拒绝同一形状,前端只需认 code 一个通道。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class QueryContractFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void pageContractHolds() throws Exception {
        String adminToken = login("admin", "admin123");

        assertEquals(0, codeOf(page("/system/tenant/page", adminToken)), "无参分页应走默认值路径");
        assertEquals(0, codeOf(page("/system/log/oper/page", adminToken)), "日志域无参分页应走 1/20 默认路径");

        assertRejected400(page("/system/tenant/page?pageSize=201", adminToken), "200");
        assertRejected400(page("/system/tenant/page?pageNum=0", adminToken), "页码");
        assertRejected400(page("/system/user/page?status=5", adminToken), "状态");
        assertRejected400(page("/system/log/oper/page?beginTime=not-a-time", adminToken), null);
    }

    // ------------------------------------------------------------------ 断言与调用辅助

    private void assertRejected400(String response, String needleInMessage) {
        assertEquals(400, Integer.parseInt(extract(CODE, response)), "应回 400 信封: " + response);
        if (needleInMessage != null) {
            assertTrue(response.contains(needleInMessage), "拒绝文案未带口径关键字: " + response);
        }
    }

    private String page(String urlWithQuery, String token) throws Exception {
        return mockMvc.perform(get(urlWithQuery)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int codeOf(String response) {
        return Integer.parseInt(extract(CODE, response));
    }

    private String login(String username, String password) throws Exception {
        String captchaKey = "qc-" + System.nanoTime();
        String redisKey = "snail:auth:captcha:" + captchaKey;
        redisTemplate.opsForValue().set(redisKey, "ABCD", Duration.ofMinutes(2));
        try {
            String response = mockMvc.perform(post("/system/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                    + "\",\"captchaKey\":\"" + captchaKey + "\",\"captchaCode\":\"ABCD\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(response.contains("\"token\""), "登录失败: " + response);
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
}
