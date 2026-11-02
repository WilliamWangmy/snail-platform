package com.snail.platform.function.system.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:开通租户的出参——只有新租户ID。首任管理员口令由平台管理员自输(走 HTTPS 请求体),
 * 响应不再回传口令,"服务端生成口令回传"链路已随管理员自输方案退役(P2-3)。
 * 上游:TenantController;下游:无。
 */
@Schema(description = "开通租户结果")
public record TenantCreatedVO(

        @Schema(description = "新租户ID")
        Long tenantId) {
}
