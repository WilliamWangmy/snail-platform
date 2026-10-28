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
 * 职责:通知中心端到端回归——公告扇出、收件箱隔离(他人行按"不存在")、未读数与已读闭环、
 * 收件箱移除、平台公告列表、出站通道兜底落库(log 通道)。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 注入口令,未设则整类跳过);
 * 下游:无断言外设。用户/角色/公告有意残留,与阶段③流程测试同一口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class NotifyFlowTest {

    /** 全链 MVC:认证上下文、租户拦截器、通知服务全部真实参与 */
    @Autowired
    private MockMvc mockMvc;

    /** 与业务同一套 Redis 客户端,用于预置验证码答案 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 角色授权直插:给测试角色补通知中心菜单码 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern DATA_ID = Pattern.compile("\"data\":(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern TOTAL = Pattern.compile("\"total\":(\\d+)");
    private static final Pattern RECIPIENT_ID = Pattern.compile("\"recipientId\":(\\d+)");
    private static final Pattern UNREAD = Pattern.compile("\"readStatus\":0");

    private static final String PASSWORD = "User@1234";

    @Test
    void notifyFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        // 造一个持通知菜单码的普通用户,验证收件箱隔离与扇出
        long roleId = createRoleWithNotify(suffix, adminToken);
        String username = "notifyU" + suffix;
        createUser(username, roleId, adminToken);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        String userToken = login(username, PASSWORD);

        assertEquals(0, unreadCount(userToken), "新用户收件箱应为空: ");

        // 平台公告 → 租户 1 全员扇出
        String title = "公告" + suffix;
        String send = postJson("/platform/notify/announcement",
                "{\"tenantId\":1,\"title\":\"" + title + "\",\"content\":\"内容" + suffix + "\"}", adminToken);
        assertEquals(0, jsonCode(send), "发送公告失败: " + send);

        // 双方收件箱都见到公告,均为未读
        assertTrue(inbox(userToken).contains(q(title)), "扇出后用户收件箱未见公告: ");
        long userUnread = unreadCount(userToken);
        assertTrue(userUnread >= 1, "公告后未读数应至少为 1: " + userUnread);
        assertTrue(inbox(adminToken).contains(q(title)), "扇出后管理员收件箱未见公告: ");

        // 收件箱隔离:用户读管理员的收件行按"不存在"拒绝
        long adminRecipientId = firstRecipientId(inbox(adminToken), title);
        String foreign = mockMvc.perform(get("/system/notify/" + adminRecipientId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(jsonCode(foreign) != 0, "他人收件行不应可读: " + foreign);

        // 已读闭环:标已读 → 未读数回落 → read-all 清零
        long recipientId = firstRecipientId(inbox(userToken), title);
        String read = putJson("/system/notify/" + recipientId + "/read", userToken);
        assertEquals(0, jsonCode(read), "标记已读失败: " + read);
        assertEquals(userUnread - 1, unreadCount(userToken), "已读后未读数未回落: ");
        assertTrue(!detail(userToken, recipientId).contains(UNREAD.pattern()), "已读后详情仍含未读标记: ");

        // 平台公告列表可见本次公告
        assertTrue(platformPage(adminToken).contains(q(title)), "平台公告列表未见公告: ");

        // 收件箱移除 → 列表不再出现
        String remove = mockMvc.perform(delete("/system/notify/" + recipientId)
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(remove), "移出收件箱失败: " + remove);
        assertFalse(inbox(userToken).contains(q(title)), "移除后收件箱仍见公告: ");

        // 出站兜底通道落库:无邮件配置时应有 channel=log 的成功记录(异步,轮询等待)
        long messageId = extractLong(detail(adminToken, adminRecipientId), Pattern.compile("\"messageId\":(\\d+)"));
        assertTrue(awaitOutboundRow(messageId), "出站投递记录未落库: message=" + messageId);

        // 目标租户不存在 → 业务拒绝
        String badTenant = postJson("/platform/notify/announcement",
                "{\"tenantId\":99999999,\"title\":\"x\",\"content\":\"y\"}", adminToken);
        assertTrue(jsonCode(badTenant) != 0, "不存在的租户应被拒绝: " + badTenant);
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private long createRoleWithNotify(String suffix, String adminToken) throws Exception {
        String response = postJson("/system/role", "{\"roleName\":\"通知角色" + suffix
                + "\",\"roleCode\":\"ntf" + suffix + "\",\"dataScope\":4,\"sort\":9}", adminToken);
        assertTrue(response.contains("\"code\":0"), "建角色失败: " + response);
        long roleId = Long.parseLong(extract(DATA_ID, response));
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 160)", roleId);
        return roleId;
    }

    private void createUser(String username, long roleId, String adminToken) throws Exception {
        String response = postJson("/system/user", "{\"username\":\"" + username + "\",\"password\":\""
                + PASSWORD + "\",\"nickname\":\"通知用户\",\"orgId\":1,\"roleIds\":[" + roleId + "]}", adminToken);
        assertTrue(response.contains("\"code\":0"), "建用户失败: " + response);
    }

    private String inbox(String token) throws Exception {
        return mockMvc.perform(get("/system/notify/page").param("pageSize", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String platformPage(String token) throws Exception {
        return mockMvc.perform(get("/platform/notify/page").param("pageSize", "50")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long unreadCount(String token) throws Exception {
        String response = mockMvc.perform(get("/system/notify/unread-count")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(extract(DATA_ID, response));
    }

    private String detail(String token, long recipientId) throws Exception {
        return mockMvc.perform(get("/system/notify/" + recipientId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String putJson(String url, String token) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 标题所在收件行的 ID:VO 字段序 recipientId -> messageId -> title,同片段内可一并匹配 */
    private long firstRecipientId(String inboxJson, String title) {
        Pattern rowOfTitle = Pattern.compile("\"recipientId\":(\\d+),\"messageId\":\\d+,\"title\":\""
                + Pattern.quote(title) + "\"");
        return Long.parseLong(extract(rowOfTitle, inboxJson));
    }

    private boolean awaitOutboundRow(long messageId) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM sys_notify_outbound WHERE message_id = ? AND status = 1",
                    Integer.class, messageId);
            if (count != null && count > 0) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private static long extractLong(String text, Pattern pattern) {
        return Long.parseLong(extract(pattern, text));
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

    private static String q(String s) {
        return "\"" + s + "\"";
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
