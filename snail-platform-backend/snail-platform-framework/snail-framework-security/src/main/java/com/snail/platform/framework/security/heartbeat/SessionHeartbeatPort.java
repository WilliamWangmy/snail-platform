package com.snail.platform.framework.security.heartbeat;

import com.snail.platform.framework.security.token.SnailPrincipal;

/**
 * 职责:认证请求的在线心跳端口——每认证请求调用一次,实现侧记录"谁在线、从哪来、最近活跃";
 * 在线观测是辅助面,实现必须自行容错,任何异常不得阻断认证主链。
 * 上游:SnailAuthFilter 经 ObjectProvider 调用,端口缺省时跳过;下游:function 侧在线会话实现。
 * 口径:ip 取请求直连地址,代理链路如需真实来源由实现/网关侧扩展。
 */
public interface SessionHeartbeatPort {

    /** 记录一次活跃;principal 携带令牌身份,ip/ua 由过滤器采集 */
    void beat(SnailPrincipal principal, String ip, String userAgent);
}
