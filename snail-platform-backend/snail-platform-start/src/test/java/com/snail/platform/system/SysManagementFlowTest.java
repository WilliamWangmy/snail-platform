package com.snail.platform.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 职责:阶段③系统管理 CRUD 的端到端回归——租户开通(模板复制)、新租户登录、租户隔离
 * (拦截器正向注入首次实证)、组织树守卫、角色/用户守护分支与授权全量覆盖;
 * 阶段⑥补齐修改类写端点全覆盖:租户/组织/角色/用户改名、角色停用启用、重置口令闭环、停用账号登录被拒。
 * 上游:snail-platform-start 装配后的真实应用上下文(需本机 MySQL/Valkey,经 SNAIL_DB_* 环境变量注入口令);
 * 下游:无断言外设。未设 SNAIL_DB_USERNAME 时整类跳过,不拖累离线构建。
 * 口径:单流程方法顺序走完全部步骤,共享上下文只在方法内;每次运行用纳秒后缀生成互不冲突的
 * 租户码/登录名,可重复执行;业务拒绝断 HTTP 200 + code=400 信封。
 * 残留:演示租户与其管理员有意保留——销户是运维动作,系统管理不提供删租户入口(设计文档 §2 口径)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class SysManagementFlowTest {

    /** 全链 MVC,走真实安全过滤链与租户拦截器 */
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
    private static final Pattern CODE = Pattern.compile("\"code\":(\\d+)");

    @Test
    void crudFlowEndToEnd() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100_000_000L);
        String adminToken = login("admin", "admin123");

        Long demoTenantId = provisionTenant(suffix, adminToken);
        TestPasswordFlagSupport.clearPendingFlags(jdbcTemplate);
        proveTenantRenamed(demoTenantId, suffix, adminToken);
        String demoToken = login("tadmin" + suffix, "Admin@123");
        proveTemplateCopied(suffix, demoToken);
        RoleIds roleIds = proveRoleIsolation(suffix, adminToken, demoToken);

        OrgGuardFixtures guards = exerciseOrgGuards(suffix, adminToken);
        exerciseUserGuardsAndAssignment(suffix, adminToken, roleIds.demoAuditRoleId());
        exerciseRoleGuards(adminToken, roleIds.scratchTenant1RoleId());
        proveDisabledTenantBlocksLogin(suffix, adminToken, demoTenantId);

        // 租户1内的临时审计员:非内置未授权,可删——顺带验证删除主链路
        String deleted = mockMvc.perform(delete("/system/role/" + roleIds.scratchTenant1RoleId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(deleted), "删除临时角色失败: " + deleted);
        assertRoleGoneFromPage(roleIds.scratchTenant1RoleId(), adminToken);

        cleanup(adminToken, guards);
        assertNotNull(demoTenantId);
    }

    private record RoleIds(Long demoAuditRoleId, Long scratchTenant1RoleId) {
    }

    private Long provisionTenant(String suffix, String adminToken) throws Exception {
        String body = """
                {"tenantCode":"demo%s","name":"演示公司%s","contactUser":"张三","contactPhone":"13900000000",
                 "expireTime":null,"accountLimit":5,"remark":"集成测试开通",
                 "adminUsername":"tadmin%s","adminPassword":"Admin@123"}
                """.formatted(suffix, suffix, suffix);
        String response = mockMvc.perform(post("/system/tenant")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"code\":0"), "开通租户失败: " + response);
        return Long.valueOf(extract(DATA_ID, response));
    }

    /** 模板复制的管理员可登录 = 根组织/内置角色/账号/绑定四步写入全部落到了新租户头上 */
    private void proveTemplateCopied(String suffix, String demoToken) throws Exception {
        String tree = mockMvc.perform(get("/system/org/tree")
                        .header("Authorization", "Bearer " + demoToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(tree.contains("演示公司" + suffix), "新租户根组织缺失: " + tree);
        assertFalse(tree.contains("\"name\":\"Snail\""), "新租户看到了租户1的组织——拦截器未生效: " + tree);
    }

    /** role_code 仅租户内唯一 + 租户1列表看不到演示租户角色(正向注入首次实证) */
    private RoleIds proveRoleIsolation(String suffix, String adminToken, String demoToken) throws Exception {
        String body = "{\"roleName\":\"审计员\",\"roleCode\":\"audit" + suffix + "\",\"dataScope\":2,\"sort\":9}";
        String created = postJson("/system/role", body, demoToken);
        assertTrue(created.contains("\"code\":0"), "演示租户建角色失败: " + created);
        Long demoAuditRoleId = Long.valueOf(extract(DATA_ID, created));

        String reuse = postJson("/system/role", body, adminToken);
        assertTrue(reuse.contains("\"code\":0"), "role_code 应仅租户内唯一: " + reuse);
        Long scratchTenant1RoleId = Long.valueOf(extract(DATA_ID, reuse));

        String adminPage = mockMvc.perform(get("/system/role/page").param("pageSize", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(adminPage.contains("\"roleId\":" + demoAuditRoleId + ","),
                "租户1看到了租户2的角色——租户过滤正向注入未生效: " + adminPage);
        assertTrue(adminPage.contains("\"roleId\":" + scratchTenant1RoleId + ","),
                "租户1自己的临时角色应当可见: " + adminPage);
        return new RoleIds(demoAuditRoleId, scratchTenant1RoleId);
    }

    private record OrgGuardFixtures(Long orgA, Long orgB) {
    }

    private OrgGuardFixtures exerciseOrgGuards(String suffix, String adminToken) throws Exception {
        Long orgA = Long.valueOf(createOrg(1L, "守卫A" + suffix, adminToken));
        Long orgB = Long.valueOf(createOrg(orgA, "守卫B" + suffix, adminToken));

        // 修改端点覆盖面:改名落到树上(username 类"建后不可改"键不在契约里,这里只动可改字段)
        String renamedOrg = putJson("/system/org/" + orgA,
                "{\"name\":\"守卫A改名" + suffix + "\",\"sort\":0,\"status\":null,\"remark\":null}", adminToken);
        assertEquals(0, jsonCode(renamedOrg), "修改组织失败: " + renamedOrg);
        String tree = mockMvc.perform(get("/system/org/tree")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(tree.contains("守卫A改名" + suffix), "组织改名未落树: " + tree);

        String moveToOwnSubtree = mockMvc.perform(put("/system/org/" + orgA + "/move")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newParentId\":" + orgB + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(moveToOwnSubtree.contains("不能把组织移入自己的子树"), "移入自身子树未被拒: " + moveToOwnSubtree);

        String deleteWithChild = mockMvc.perform(delete("/system/org/" + orgA)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(deleteWithChild.contains("存在下级组织"), "有子节点删除未被拒: " + deleteWithChild);

        // 新父 0 不是可引用节点:根只能由开通模板生成
        String moved = mockMvc.perform(put("/system/org/" + orgB + "/move")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newParentId\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(moved.contains("组织不存在"), "新父为 0 不是合法组织: " + moved);
        return new OrgGuardFixtures(orgA, orgB);
    }

    private void exerciseUserGuardsAndAssignment(String suffix, String adminToken, long foreignDemoRoleId)
            throws Exception {
        // 独立叶子组织挂用户:专测"组织下存在用户即拒",不受根下其它临时节点干扰
        Long leafOrgId = Long.valueOf(createOrg(1L, "挂人叶子" + suffix, adminToken));

        String username = "suser" + suffix;
        String body = """
                {"username":"%s","password":"User@1234","nickname":"测试用户","email":"t@example.com",
                 "phone":"13800000000","orgId":%d,"remark":null,"roleIds":[2]}
                """.formatted(username, leafOrgId);
        String created = postJson("/system/user", body, adminToken);
        assertTrue(created.contains("\"code\":0"), "建用户失败: " + created);
        long scratchUserId = Long.parseLong(extract(DATA_ID, created));

        String deleteOrgWithUser = mockMvc.perform(delete("/system/org/" + leafOrgId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(deleteOrgWithUser.contains("组织下存在用户"), "挂着用户的组织删除未被拒: " + deleteOrgWithUser);

        // 全局登录名查重:演示租户管理员已占用 tadminX,租户1内再用同名必须被拒
        String dup = postJson("/system/user", body.replace(username, "tadmin" + suffix), adminToken);
        assertTrue(dup.contains("登录名已存在"), "跨租户重名未被全局口径拒绝: " + dup);

        String detail = mockMvc.perform(get("/system/user/" + scratchUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(detail.contains("\"roleIds\":[2]"), "建号即授权未生效: " + detail);
        assertFalse(detail.contains("assword"), "出参泄漏密文字段: " + detail);

        String reassigned = mockMvc.perform(put("/system/user/" + scratchUserId + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(reassigned), "清空授权失败: " + reassigned);
        String afterClear = mockMvc.perform(get("/system/user/" + scratchUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(afterClear.contains("\"roleIds\":[]"), "全量覆盖未清空授权: " + afterClear);

        // 跨租户授权必须被拒:把演示租户的角色 ID 分给租户1用户
        String crossTenantGrant = mockMvc.perform(put("/system/user/" + scratchUserId + "/roles")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roleIds\":[" + foreignDemoRoleId + "]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(crossTenantGrant.contains("不存在或跨租户"), "跨租户授权未被拒: " + crossTenantGrant);

        String selfDisable = mockMvc.perform(put("/system/user/1/status").param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(selfDisable.contains("不能停用自己的账号"), "停用自己的账号未被拒: " + selfDisable);

        // 资料修改面:改显示名落详情
        String renamedUser = putJson("/system/user/" + scratchUserId,
                "{\"nickname\":\"改名后的用户\",\"email\":null,\"phone\":null,\"orgId\":" + leafOrgId
                        + ",\"remark\":\"回归改写\"}", adminToken);
        assertEquals(0, jsonCode(renamedUser), "修改用户资料失败: " + renamedUser);
        String afterRename = mockMvc.perform(get("/system/user/" + scratchUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(afterRename.contains("改名后的用户"), "用户改名未生效: " + afterRename);

        // 重置口令闭环:新口令可登、旧口令被拒;停用后同号登录被"账号已停用"拦下,再启用恢复
        String reset = putJson("/system/user/" + scratchUserId + "/password", "{\"password\":\"Reset@12345\"}", adminToken);
        assertEquals(0, jsonCode(reset), "重置口令失败: " + reset);
        assertTrue(loginAttempt(username, "Reset@12345").contains("\"token\""), "重置后新口令登不进");
        assertTrue(loginAttempt(username, "User@1234").contains("账号或密码错误"), "重置后旧口令不应生效");

        String disable = mockMvc.perform(put("/system/user/" + scratchUserId + "/status").param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(disable), "停用测试用户失败: " + disable);
        assertTrue(loginAttempt(username, "Reset@12345").contains("账号已停用"), "停用账号仍可登录");
        String enable = mockMvc.perform(put("/system/user/" + scratchUserId + "/status").param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(enable), "恢复测试用户失败: " + enable);

        // 删除主链路:先删用户(连带清授权),空叶子组织随后才可删
        String delUser = mockMvc.perform(delete("/system/user/" + scratchUserId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(delUser), "删除测试用户失败: " + delUser);
        String delLeaf = mockMvc.perform(delete("/system/org/" + leafOrgId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(delLeaf), "用户移除后叶子组织应可删除: " + delLeaf);
    }

    private void exerciseRoleGuards(String adminToken, long scratchRoleId) throws Exception {
        String deleteBuiltin = mockMvc.perform(delete("/system/role/1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(deleteBuiltin.contains("内置角色不可删除"), "删除内置角色未被拒: " + deleteBuiltin);

        String dupCode = postJson("/system/role",
                "{\"roleName\":\"重复管理员\",\"roleCode\":\"admin\",\"dataScope\":4}", adminToken);
        assertTrue(dupCode.contains("角色标识已存在"), "租户内重码未被拒: " + dupCode);

        // 修改面:改名落列表(role_code 不在契约,建后不可改);停用/启用往返一次
        String renamedRole = putJson("/system/role/" + scratchRoleId,
                "{\"roleName\":\"临时角色改名\",\"dataScope\":null,\"sort\":null,\"status\":null,\"remark\":\"回归改写\"}",
                adminToken);
        assertEquals(0, jsonCode(renamedRole), "修改角色失败: " + renamedRole);
        String page = rolePage(adminToken);
        assertTrue(page.contains("临时角色改名"), "角色改名未落列表: " + page);

        String disabled = mockMvc.perform(put("/system/role/" + scratchRoleId + "/status").param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(disabled), "停用角色失败: " + disabled);
        assertTrue(roleRow(adminToken, scratchRoleId).contains("\"status\":1"), "停用未落行: ");
        String enabled = mockMvc.perform(put("/system/role/" + scratchRoleId + "/status").param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(enabled), "恢复角色失败: " + enabled);
        assertTrue(roleRow(adminToken, scratchRoleId).contains("\"status\":0"), "恢复未落行: ");
    }

    /** 从角色列表里取指定 roleId 的行 JSON(行内无嵌套对象,花括号内匹配即可) */
    private String roleRow(String adminToken, long roleId) throws Exception {
        String page = rolePage(adminToken);
        Matcher row = Pattern.compile("\\{[^{}]*\"roleId\":" + roleId + "[^{}]*\\}").matcher(page);
        assertTrue(row.find(), "列表里找不到角色 " + roleId + ": " + page);
        return row.group();
    }

    private String rolePage(String adminToken) throws Exception {
        return mockMvc.perform(get("/system/role/page").param("pageSize", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 租户资料修改面:改名可从租户页筛出;tenant_code 不在契约,建后不可改 */
    private void proveTenantRenamed(Long tenantId, String suffix, String adminToken) throws Exception {
        String body = "{\"name\":\"改名演示公司" + suffix + "\",\"contactUser\":\"李四\",\"contactPhone\":\"13900000001\","
                + "\"expireTime\":null,\"accountLimit\":6,\"status\":null,\"remark\":\"集成测试改名\"}";
        String updated = putJson("/system/tenant/" + tenantId, body, adminToken);
        assertEquals(0, jsonCode(updated), "修改租户资料失败: " + updated);
        String page = mockMvc.perform(get("/system/tenant/page").param("name", "改名演示公司" + suffix)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(page.contains("改名演示公司" + suffix), "租户改名未落列表: " + page);
    }

    private void proveDisabledTenantBlocksLogin(String suffix, String adminToken, Long demoTenantId)
            throws Exception {
        String block = mockMvc.perform(put("/system/tenant/" + demoTenantId + "/status").param("status", "1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(block), "停用演示租户失败: " + block);

        String failed = loginAttempt("tadmin" + suffix, "Admin@123");
        assertTrue(failed.contains("租户已停用"), "停用租户后其管理员仍可登录: " + failed);

        String restore = mockMvc.perform(put("/system/tenant/" + demoTenantId + "/status").param("status", "0")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(0, jsonCode(restore), "恢复演示租户失败: " + restore);
    }

    private void cleanup(String adminToken, OrgGuardFixtures guards) throws Exception {
        mockMvc.perform(delete("/system/org/" + guards.orgB())
                .header("Authorization", "Bearer " + adminToken));
        mockMvc.perform(delete("/system/org/" + guards.orgA())
                .header("Authorization", "Bearer " + adminToken));
    }

    /** 复核临时角色已删:列表口径查不到(逻辑删后拦截器仍带 deleted=0) */
    private void assertRoleGoneFromPage(long roleId, String adminToken) throws Exception {
        String detail = mockMvc.perform(get("/system/role/page").param("pageSize", "100")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(detail.contains("\"roleId\":" + roleId + ","), "临时角色未删除干净: " + detail);
    }

    private String login(String username, String password) throws Exception {
        return extract(TOKEN, loginAttempt(username, password));
    }

    /** 带真实验证码的登录尝试,返回完整响应——成功链取令牌,失败分支断文案都靠它 */
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

    private String createOrg(Long parentId, String name, String token) throws Exception {
        String response = postJson("/system/org",
                "{\"parentId\":" + parentId + ",\"name\":\"" + name + "\",\"sort\":0}", token);
        assertTrue(response.contains("\"code\":0"), "建组织失败: " + response);
        return extract(DATA_ID, response);
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

    private static String extract(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), "响应中未找到 " + pattern + " : " + text);
        return matcher.group(1);
    }

    private static int jsonCode(String text) {
        return Integer.parseInt(extract(CODE, text));
    }
}
