package com.snail.platform.framework.security.token;

/**
 * 职责:认证通过后的身份五元组,是安全主体与租户/审计/操作人快照上下文的唯一数据来源。
 * 上游:JwtTokenService 校验通过后构造;下游:SecurityContext、TenantContextHolder、LoginUserContextHolder。
 * orgId/tokenVersion 老令牌可能没有该载荷:orgId 允许 null 按缺省处理;
 * tokenVersion 缺失按 0 计(与建列默认值同口径),保证加 ver 声明前的存量令牌平滑过渡。
 */
public record SnailPrincipal(Long userId, Long tenantId, String username, Long orgId, Long tokenVersion) {
}
