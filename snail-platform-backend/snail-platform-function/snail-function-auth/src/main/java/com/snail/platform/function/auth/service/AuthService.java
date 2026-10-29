package com.snail.platform.function.auth.service;

import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.extra.servlet.JakartaServletUtil;
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
import com.snail.platform.function.system.support.CacheEvictor;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:登录校验链的唯一实现——锁定检查 → 验证码 → 定位用户(无租户旁路窄窗口)→ 租户状态 → 用户状态 → BCrypt。
 * 每步拒绝的文案即口径;业务拒绝与成功必落一条登录日志,用户定位前的失败归属记 tenant_id=0
 * (基础设施故障走 500,不落登录日志,由应用日志排障)。
 * 上游:AuthController;下游:sys_user/sys_tenant 读、sys_login_log 写、JWT 签发。
 * 防账号枚举:用户不存在与口令错误回同一文案。
 * 计数口径:失败按类型分流(LoginRejects)——只有凭证失败累加 IP+登录名锁定,
 * 验证码失败由 CaptchaService 按 IP 自计,租户/账号停用属运营状态不计数;
 * 锁定分支自身不再累加:计数已过阈值,继续累加没有意义,窗口仍从首次失败起算。
 * 来源 IP 取 Hutool getClientIP(信任转发头),代理层必须负责归一化,否则计数可被伪造头绕开。
 */
@Service
public class AuthService {

    /** 锁定命中时的统一文案 */
    private static final String LOCKED_MESSAGE = "失败次数过多,请稍后再试";

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

    /** 令牌签发,签出的 ver 取自用户当前版本供过滤器比对 */
    private final JwtTokenService jwtTokenService;

    /** 登出时的快照点清出口 */
    private final CacheEvictor cacheEvictor;

    public AuthService(SysUserMapper userMapper, SysTenantMapper tenantMapper, SysLoginLogMapper loginLogMapper,
                       CaptchaService captchaService, LoginFailGuard failGuard, JwtTokenService jwtTokenService,
                       CacheEvictor cacheEvictor) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.loginLogMapper = loginLogMapper;
        this.captchaService = captchaService;
        this.failGuard = failGuard;
        this.jwtTokenService = jwtTokenService;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * 执行完整登录链并签发令牌;失败按 LoginRejects 分类决定是否计数。
     * 出参是登录时刻的身份与租户快照,后续资料变更以 /me 为准。
     */
    public LoginSuccessVO login(LoginRequest request, HttpServletRequest http) {
        // 先去空白:锁定键与用户定位必须用同一字面量,否则 "admin " 会分裂成另一个计数桶
        String username = request.username().trim();
        String ip = JakartaServletUtil.getClientIP(http);
        if (failGuard.isCredentialLocked(ip, username)) {
            writeLoginLog(http, 0L, username, null, 0, LOCKED_MESSAGE);
            throw BizException.badRequest(LOCKED_MESSAGE);
        }
        SysUser user = null;
        try {
            captchaService.verify(ip, request.captchaKey(), request.captchaCode());

            // 登录时还没有租户上下文:按全表唯一的登录名定位用户,旁路窗口只覆盖这一条查询
            user = TenantContextHolder.withoutTenant(() -> userMapper.selectOne(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username)));
            if (user == null) {
                throw new LoginRejects.Credential("账号或密码错误");
            }

            // 按 user.tenantId 定点取租户:sys_tenant 在拦截器 ignore 清单,这里不需要摘租户条件也查得到别的租户
            // 租户行缺失(销户/数据被清)与显式停用同口径报"租户已停用",登录方不需要分辨是哪种
            // 状态位:0 正常、1 停用,与 sys_user.status 同口径
            SysTenant tenant = tenantMapper.selectById(user.getTenantId());
            if (tenant == null || Objects.equals(tenant.getStatus(), 1)) {
                throw new LoginRejects.Access("租户已停用");
            }
            if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
                throw new LoginRejects.Access("租户已到期");
            }
            if (Objects.equals(user.getStatus(), 1)) {
                throw new LoginRejects.Access("账号已停用");
            }
            // 口令比对放在状态检查之后:前置步骤都不需要哈希,停用账号在这一步之前就被拒
            if (!BCrypt.checkpw(request.password(), user.getPassword())) {
                throw new LoginRejects.Credential("账号或密码错误");
            }

            failGuard.clearCredentialFails(ip, username);
            String token = jwtTokenService.issue(
                    new SnailPrincipal(user.getUserId(), user.getTenantId(), user.getUsername(),
                            user.getOrgId(), user.getTokenVersion()));
            writeLoginLog(http, user.getTenantId(), username, user.getUserId(), 1, "登录成功");
            return new LoginSuccessVO(token, "Bearer", jwtTokenService.expiresInSeconds(),
                    user.getUserId(), user.getUsername(), user.getNickname(),
                    user.getTenantId(), tenant.getName(),
                    user.getPasswordModifyTime() == null);
        } catch (BizException e) {
            // 登录在租户上下文建立之前就被拒(验证码/查无此人/锁定),此处只有查到的用户能给出租户;
            // 查不到的一律记 0=归属不明,这批行只有持跨租户码的平台侧看得见
            Long tenantId = user != null ? user.getTenantId() : 0L;
            Long userId = user != null ? user.getUserId() : null;
            writeLoginLog(http, tenantId, username, userId, 0, e.getMessage());
            if (e instanceof LoginRejects.Credential) {
                failGuard.increaseCredentialFail(ip, username);
            }
            throw e;
        }
    }

    /**
     * 登出:令牌版本自增,该用户全部设备立即失效;userSession 必须清掉,
     * 否则下次登录读到旧版本快照,新令牌会被过滤器判成已吊销。
     * 键里的租户段与 UserSessionService 的生产端同拼法(租户:用户);这里用上下文取租户,
     * 因为登出请求的租户上下文已由过滤器从同一枚令牌装好。
     * 点清排在事务提交之后,免得提交前的并发读把旧版本快照回填进来。
     */
    @Transactional
    public void logout(Long userId) {
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        cacheEvictor.evict("userSession", CacheEvictor.tenantKey(TenantContextHolder.get(), userId));
    }

    /** 留痕写失败不许反过来影响登录响应——库都挂了的场景没有一致性问题可言 */
    private void writeLoginLog(HttpServletRequest http, Long tenantId, String username,
                               Long userId, int status, String msg) {
        UserAgent ua = UserAgentUtil.parse(http.getHeader("User-Agent"));
        SysLoginLog log = new SysLoginLog();
        log.setTenantId(tenantId);
        log.setUsername(username);
        log.setUserId(userId);
        log.setIp(JakartaServletUtil.getClientIP(http));
        log.setBrowser(ua == null ? null : ua.getBrowser().getName());
        log.setOs(ua == null ? null : ua.getOs().getName());
        log.setStatus(status);
        log.setMsg(msg);
        log.setLoginTime(LocalDateTime.now());
        try {
            loginLogMapper.insert(log);
        } catch (RuntimeException ignored) {
            // 留痕失败直接忽略,不阻断登录响应;此处不另行记录
        }
    }
}
