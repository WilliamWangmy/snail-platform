package com.snail.platform.framework.security.authority;

import java.util.Collection;

/**
 * 职责:认证过滤器与业务权限模型之间的唯一端口——按用户 ID 取权限码集合,过滤器不查表、不认识角色/菜单。
 * 上游:framework-security 的认证过滤器;下游:function-system 的 role 子域实现(角色/菜单授权与 admin 隐式规则)。
 * 约束:JWT 只带身份四元组,权限码每请求经本端口解析;无权限返回空集合,不得返回 null。
 */
public interface UserAuthorityProvider {

    /** 返回该用户当前生效的权限码;调用时租户上下文已就绪 */
    Collection<String> authoritiesOf(Long userId);
}
