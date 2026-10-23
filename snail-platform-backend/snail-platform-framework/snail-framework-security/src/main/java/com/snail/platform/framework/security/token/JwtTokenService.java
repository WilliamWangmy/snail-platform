package com.snail.platform.framework.security.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.security.SnailSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 职责:平台 JWT 的唯一签发与校验处,HS256,载荷只放 uid/tid/uname/oid 四项身份事实。
 * 上游:SnailSecurityConfig 装配;下游:认证链签发、过滤器回验。
 * 不做:refresh token、token 黑名单——无状态到期即失效是本期口径。
 */
public class JwtTokenService {

    /** 用户 ID 载荷键 */
    private static final String CLAIM_UID = "uid";

    /** 租户 ID 载荷键 */
    private static final String CLAIM_TID = "tid";

    /** 登录名载荷键,日志快照直接取用免回查 */
    private static final String CLAIM_UNAME = "uname";

    /** 组织 ID 载荷键,操作日志按部门留痕用;此键加入前签发的令牌无该claim,回验得 null */
    private static final String CLAIM_OID = "oid";

    /** HS256 签名密钥 */
    private final byte[] secret;

    /** 令牌有效期 */
    private final Duration ttl;

    public JwtTokenService(SnailSecurityProperties properties) {
        String configured = properties.getJwt().getSecret();
        if (configured == null || configured.length() < 32) {
            throw new IllegalStateException("snail.security.jwt.secret 缺失或短于 32 字符,拒绝启动");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofMinutes(properties.getJwt().getExpireMinutes());
    }

    /** 签发访问令牌 */
    public String issue(SnailPrincipal principal) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(principal.userId()))
                .claim(CLAIM_UID, principal.userId())
                .claim(CLAIM_TID, principal.tenantId())
                .claim(CLAIM_UNAME, principal.username())
                .claim(CLAIM_OID, principal.orgId())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).build(), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("JWT 签发失败", e);
        }
        return jwt.serialize();
    }

    /** 有效期秒数,登录响应回带给前端做过期提示 */
    public long expiresInSeconds() {
        return ttl.toSeconds();
    }

    /**
     * 校验签名与有效期,任何不合法(篡改/过期/格式错)统一 401 文案,不区分原因防探测。
     */
    public SnailPrincipal verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret))) {
                throw BizException.unauthorized("登录状态无效,请重新登录");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) {
                throw BizException.unauthorized("登录已过期,请重新登录");
            }
            return new SnailPrincipal(
                    claims.getLongClaim(CLAIM_UID),
                    claims.getLongClaim(CLAIM_TID),
                    claims.getStringClaim(CLAIM_UNAME),
                    claims.getLongClaim(CLAIM_OID));
        } catch (ParseException | JOSEException e) {
            throw BizException.unauthorized("登录状态无效,请重新登录");
        }
    }
}
