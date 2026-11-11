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
        List<String> permissions,

        @Schema(description = "是否待强制改密:true=须先完成强制改密才能使用业务功能")
        boolean passwordChangeRequired,

        @Schema(description = "租户五系统开通态,实时查 sys_tenant;登录快照不含此字段(仅 /me 组装时填充),开关改动对下一次 /me 即时生效")
        TenantFeatures tenantFeatures) {

    @Schema(description = "角色摘要")
    public record RoleInfo(

            @Schema(description = "角色ID")
            Long roleId,

            @Schema(description = "角色标识")
            String roleCode,

            @Schema(description = "角色名称")
            String roleName) {
    }

    /**
     * 租户五系统开通态:三端路由守卫的消费面——对应开关为 false 即登录被拒/落地即踢。
     * boolean 而非 Integer:开关语义在契约层就是"开通/未开通",0/1 编码留在表列层。
     */
    @Schema(description = "租户五系统开通态")
    public record TenantFeatures(

            @Schema(description = "管理系统开通")
            boolean adminEnabled,

            @Schema(description = "流程系统开通")
            boolean workflowEnabled,

            @Schema(description = "移动端(app 与小程序)开通")
            boolean mobileEnabled,

            @Schema(description = "数据大屏开通")
            boolean dashboardEnabled,

            @Schema(description = "企业门户开通")
            boolean portalEnabled) {
    }

    public static LoginUserVO of(LoginUser user, Collection<String> permissions) {
        return new LoginUserVO(user.userId(), user.username(), user.nickname(), user.tenantId(),
                user.tenantName(), user.orgId(), user.orgName(),
                user.roles().stream()
                        .map(role -> new RoleInfo(role.roleId(), role.roleCode(), role.roleName()))
                        .toList(),
                permissions.stream().sorted().toList(),
                user.passwordChangeRequired(),
                // 登录快照不含开通态(null 占位):开关改动要对着存量会话即时生效,快照填充会让改动滞后到重新登录
                null);
    }

    /**
     * /me 专用:在快照投影上补充实时开通态(record 不可变,拷贝式赋值)。
     * 组装点在 AuthController.me()——AuthService 提供 tenantFeaturesOf 查询,快照与开通态在此汇合。
     */
    public LoginUserVO withTenantFeatures(TenantFeatures features) {
        return new LoginUserVO(userId, username, nickname, tenantId, tenantName, orgId, orgName,
                roles, permissions, passwordChangeRequired, features);
    }
}
