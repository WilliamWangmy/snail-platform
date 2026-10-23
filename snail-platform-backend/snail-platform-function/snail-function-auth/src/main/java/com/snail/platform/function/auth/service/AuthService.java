package com.snail.platform.function.auth.service;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.http.useragent.UserAgent;
import cn.hutool.http.useragent.UserAgentUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.security.token.JwtTokenService;
import com.snail.platform.framework.security.token.SnailPrincipal;
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.auth.dto.LoginSuccessVO;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import com.snail.platform.function.system.log.mapper.SysLoginLogMapper;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 职责:登录校验链的唯一实现——锁定检查 → 验证码 → 定位用户(无租户旁路窄窗口)→ 租户状态 → 用户状态 → BCrypt。
 * 每步拒绝的文案即口径;凡进入本服务必落一条登录日志,成败皆记,用户不存在的失败归属记 tenant_id=0。
 * 上游:AuthController;下游:sys_user/sys_tenant 读、sys_login_log 写、JWT 签发。
 * 防账号枚举:用户不存在与口令错误回同一文案。
 */
@Service
public class AuthService {

    /** 用户表读,登录定位走旁路窗口 */
    private final SysUserMapper userMapper;

    /** 租户表读,豁免表 */
    private final SysTenantMapper tenantMapper;

    /** 登录日志写,豁免表 */
    private final SysLoginLogMapper loginLogMapper;

    /** 验证码核销 */
    private final CaptchaService captchaService;

    /** 暴力计数 */
    private final LoginFailGuard failGuard;

    /** 令牌签发 */
    private final JwtTokenService jwtTokenService;

    public AuthService(SysUserMapper userMapper, SysTenantMapper tenantMapper, SysLoginLogMapper loginLogMapper,
                       CaptchaService captchaService, LoginFailGuard failGuard, JwtTokenService jwtTokenService) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.loginLogMapper = loginLogMapper;
        this.captchaService = captchaService;
        this.failGuard = failGuard;
        this.jwtTokenService = jwtTokenService;
    }

    public LoginSuccessVO login(LoginRequest request, HttpServletRequest http) {
        String username = request.username().trim();
        SysUser user = null;
        try {
            if (failGuard.isLocked(username)) {
                throw BizException.badRequest("失败次数过多,请稍后再试");
            }
            captchaService.verify(request.captchaKey(), request.captchaCode());

            user = TenantContextHolder.withoutTenant(() -> userMapper.selectOne(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)));
            if (user == null) {
                throw BizException.badRequest("账号或密码错误");
            }

            SysTenant tenant = tenantMapper.selectById(user.getTenantId());
            if (tenant == null || Objects.equals(tenant.getStatus(), 1)) {
                throw BizException.badRequest("租户已停用");
            }
            if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
                throw BizException.badRequest("租户已到期");
            }
            if (Objects.equals(user.getStatus(), 1)) {
                throw BizException.badRequest("账号已停用");
            }
            if (!BCrypt.checkpw(request.password(), user.getPassword())) {
                throw BizException.badRequest("账号或密码错误");
            }

            failGuard.clear(username);
            String token = jwtTokenService.issue(
                    new SnailPrincipal(user.getUserId(), user.getTenantId(), user.getUsername(),
                            user.getOrgId()));
            writeLoginLog(http, user.getTenantId(), username, user.getUserId(), 1, "登录成功");
            return new LoginSuccessVO(token, "Bearer", jwtTokenService.expiresInSeconds(),
                    user.getUserId(), user.getUsername(), user.getNickname(),
                    user.getTenantId(), tenant.getName());
        } catch (BizException e) {
            Long tenantId = user != null ? user.getTenantId() : 0L;
            Long userId = user != null ? user.getUserId() : null;
            writeLoginLog(http, tenantId, username, userId, 0, e.getMessage());
            failGuard.increase(username);
            throw e;
        }
    }

    /** 留痕写失败不许反过来影响登录响应——库都挂了的场景没有一致性问题可言 */
    private void writeLoginLog(HttpServletRequest http, Long tenantId, String username,
                               Long userId, int status, String msg) {
        UserAgent ua = UserAgentUtil.parse(http.getHeader("User-Agent"));
        SysLoginLog log = new SysLoginLog();
        log.setTenantId(tenantId);
        log.setUsername(username);
        log.setUserId(userId);
        log.setIp(cn.hutool.extra.servlet.JakartaServletUtil.getClientIP(http));
        log.setBrowser(ua == null ? null : ua.getBrowser().getName());
        log.setOs(ua == null ? null : ua.getOs().getName());
        log.setStatus(status);
        log.setMsg(msg);
        log.setLoginTime(LocalDateTime.now());
        try {
            loginLogMapper.insert(log);
        } catch (RuntimeException ignored) {
            // 登录留痕失败不阻断响应;排障看应用日志
        }
    }
}
