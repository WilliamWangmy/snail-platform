package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:个人中心档案出参——本人视角的自我投影,不含 password/tokenVersion 等安全字段。
 * roles 为本人授权角色的名称快照(只读展示,授权调整走用户管理域)。
 * 上游:ProfileService.me;下游:个人中心页面。
 */
@Schema(description = "个人档案")
public record ProfileVO(

        @Schema(description = "用户 ID")
        Long userId,

        @Schema(description = "登录名,建后不可改")
        String username,

        @Schema(description = "显示名")
        String nickname,

        @Schema(description = "邮箱")
        String email,

        @Schema(description = "手机号")
        String phone,

        @Schema(description = "所属组织")
        String orgName,

        @Schema(description = "所属租户")
        String tenantName,

        @Schema(description = "授权角色名称列表")
        List<String> roles,

        @Schema(description = "创建时间")
        LocalDateTime createTime) {
}
