package com.snail.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 职责:接口文档链路的冒烟回归——springdoc 是否真接线、文档是否覆盖关键端点与分页参数、UI 页是否可访问。
 * 上游:snail-platform-start 真实上下文(需本机 MySQL/Valkey,SNAIL_DB_* 未设则整类跳过);下游:无断言外设。
 * 口径:文档路径在安全白名单内,本测试不经登录直取 /v3/api-docs;断言取子串,不锁 JSON 结构版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")
class ApiDocsSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void openApiCoversKeyEndpoints() throws Exception {
        String doc = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(doc.contains("\"/system/auth/login\""), "文档缺登录端点");
        assertTrue(doc.contains("\"/system/user/page\""), "文档缺用户分页端点");
        assertTrue(doc.contains("\"/system/log/oper/export\""), "文档缺导出端点");
        assertTrue(doc.contains("pageNum"), "文档未展开 Query 的分页参数");
        assertTrue(doc.contains("bearerAuth"), "文档缺 JWT bearer 安全方案");
    }

    @Test
    void swaggerUiIsReachable() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
