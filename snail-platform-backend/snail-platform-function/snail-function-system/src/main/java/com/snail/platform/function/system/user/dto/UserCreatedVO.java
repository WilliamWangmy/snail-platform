package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 职责:建号与重置口令的出参——新用户ID + 服务端生成的明文口令。
 * 明文必须挂在 password 键下出参:OperLog 落库走 SensitiveDataMasker 键级脱敏,键名在名单内才会打码,
 * 裸字符串直挂 data 会绕过名单,明文就进 sys_oper_log.result 了。
 * 上游:UserController;下游:无。
 */
@Schema(description = "建号/重置口令结果")
public record UserCreatedVO(

        @Schema(description = "新用户ID")
        Long userId,

        @Schema(description = "明文口令,仅本次响应可见")
        String password) {
}
