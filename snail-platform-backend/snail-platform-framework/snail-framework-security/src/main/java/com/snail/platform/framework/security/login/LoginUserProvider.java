package com.snail.platform.framework.security.login;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.security.token.SnailPrincipal;

/**
 * 职责:认证过滤器与人员/组织/角色模型之间的唯一端口——按令牌身份补全登录人快照,过滤器不查表。
 * 上游:framework-security 的认证过滤器;下游:function-system 的 user 子域实现(带缓存与失效)。
 * 约束:调用时租户上下文已就绪;实现必须返回非 null,用户已不存在时返回令牌身份最小快照。
 */
public interface LoginUserProvider {

    /** 返回该用户的登录人快照;常用字段由实现缓存,不得每请求回查数据库 */
    LoginUser load(SnailPrincipal principal);

    /** 解析端口缺省时的兜底工厂,过滤器与实现共用同一最小快照口径 */
    static LoginUser minimal(SnailPrincipal principal) {
        return LoginUser.minimal(principal.userId(), principal.tenantId(), principal.username(), principal.orgId());
    }
}
