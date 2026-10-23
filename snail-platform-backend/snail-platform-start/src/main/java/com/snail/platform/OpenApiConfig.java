package com.snail.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:接口文档头与鉴权方案的唯一装配点——定 OpenAPI 标题/版本,并注册 JWT bearer 方案,
 * 让 Swagger UI 的 Authorize 填 access_token 即可调认证接口;端点描述不在这里聚合,各 Controller 的 @Operation 自述。
 * 上游:springdoc 扫描本模块 Bean 与全部 @RestController;下游:/v3/api-docs 的 JSON 与 /swagger-ui 页面。
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI snailPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Snail Platform API")
                        .description("多租户管理端接口:认证、租户/组织/角色/用户、日志与导出。")
                        .version("0.0.1"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
