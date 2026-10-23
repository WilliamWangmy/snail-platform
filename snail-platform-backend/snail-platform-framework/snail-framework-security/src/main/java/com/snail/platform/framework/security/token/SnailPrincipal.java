package com.snail.platform.framework.security.token;

/**
 * 职责:认证通过后的身份四元组,是安全主体与租户/审计/操作人快照上下文的唯一数据来源。
 * 上游:JwtTokenService 校验通过后构造;下游:SecurityContext、TenantContextHolder、AuditActorHolder、OperatorContextHolder。
 * orgId 老令牌可能没有该载荷,允许 null,消费方按缺省处理。
 */
public record SnailPrincipal(Long userId, Long tenantId, String username, Long orgId) {
}
