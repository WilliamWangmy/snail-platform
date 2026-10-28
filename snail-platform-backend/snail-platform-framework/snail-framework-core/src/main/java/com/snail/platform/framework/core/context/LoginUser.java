package com.snail.platform.framework.core.context;

import java.util.List;

/**
 * 职责:当前登录人的请求级富快照——令牌身份 + 租户名/显示名/组织名/生效角色,由认证过滤器一次装配。
 * 上游:snail-framework-security 经 LoginUserProvider 端口解析,写入 LoginUserContextHolder;
 * 下游:业务侧取当前登录人的人员/组织/角色信息,常用字段不必再回查数据库。
 * 口径:roles 只含启用角色并按显示顺序排列,恒非 null;本快照不参与判权,判权仍由权限码每请求解析;
 * tokenVersion 是令牌吊销口径的请求侧凭据——认证过滤器拿它与令牌 ver 声明比对,不一致视同令牌已被吊销;
 * 最小快照(用户已删/停用)不带版本(null),版本比对跳过,由角色清空与权限码口径兜底。
 */
public record LoginUser(
        /** 用户 ID */
        Long userId,
        /** 租户 ID */
        Long tenantId,
        /** 租户名称 */
        String tenantName,
        /** 登录名 */
        String username,
        /** 显示名 */
        String nickname,
        /** 所属组织 ID */
        Long orgId,
        /** 所属组织名称 */
        String orgName,
        /** 令牌版本,改密/重置/停用/强制下线时自增;最小快照为 null */
        Long tokenVersion,
        /** 生效角色,按 sort/roleId 升序 */
        List<RoleInfo> roles,
        /** 是否待强制改密:由 sys_user.password_modify_time IS NULL 派生;最小快照恒为 false */
        boolean passwordChangeRequired) {

    /** 角色摘要:roleCode 供前端判角,roleName 供展示 */
    public record RoleInfo(
            /** 角色 ID */
            Long roleId,
            /** 角色标识,如 admin */
            String roleCode,
            /** 角色名称 */
            String roleName) {
    }

    public LoginUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** 仅有令牌身份的最小快照:用户已不存在或解析端口缺省时的兜底 */
    public static LoginUser minimal(Long userId, Long tenantId, String username, Long orgId) {
        return new LoginUser(userId, tenantId, null, username, null, orgId, null, null, List.of(), false);
    }
}
