package com.snail.platform.function.auth.dto;

import com.snail.platform.framework.core.context.LoginUser;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.List;

/**
 * 职责:当前登录人出参——身份 + 人员/租户/组织名称 + 生效角色 + 权限码;常用信息一次取齐,不必再拉用户详情。
 * 上游:AuthController 取 LoginUserContextHolder 的请求级快照与权限码端口;下游:前端会话存储、路由与按钮判权。
 */
@Schema(description = "当前登录人信息")
public record LoginUserVO(

        @Schema(description = "用户ID")
        Long userId,

        @Schema(description = "登录名")
        String username,

        @Schema(description = "显示名")
        String nickname,

        @Schema(description = "租户ID")
        Long tenantId,

        @Schema(description = "租户名称")
        String tenantName,

        @Schema(description = "所属组织ID")
        Long orgId,

        @Schema(description = "所属组织名称")
        String orgName,

        @Schema(description = "生效角色,按显示顺序")
        List<RoleInfo> roles,

        @Schema(description = "生效权限码,字典序")
        List<String> permissions) {

    @Schema(description = "角色摘要")
    public record RoleInfo(

            @Schema(description = "角色ID")
            Long roleId,

            @Schema(description = "角色标识")
            String roleCode,

            @Schema(description = "角色名称")
            String roleName) {
    }

    public static LoginUserVO of(LoginUser user, Collection<String> permissions) {
        return new LoginUserVO(user.userId(), user.username(), user.nickname(), user.tenantId(),
                user.tenantName(), user.orgId(), user.orgName(),
                user.roles().stream()
                        .map(role -> new RoleInfo(role.roleId(), role.roleCode(), role.roleName()))
                        .toList(),
                permissions.stream().sorted().toList());
    }
}
