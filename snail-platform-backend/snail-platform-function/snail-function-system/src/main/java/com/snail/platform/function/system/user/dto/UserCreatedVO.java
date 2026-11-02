package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:建号出参——只有新用户ID。初始口令由管理员自输(走 HTTPS 请求体),响应不再回传口令,
 * "服务端生成口令回传"链路已随管理员自输方案退役(P2-3)。
 * 上游:UserController;下游:无。
 */
@Schema(description = "建号结果")
public record UserCreatedVO(

        @Schema(description = "新用户ID")
        Long userId) {
}
