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
import com.snail.platform.framework.redis.SnailRateGuard;
import com.snail.platform.function.auth.dto.LoginAccountsVO;
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.auth.dto.LoginSuccessVO;
import com.snail.platform.function.auth.dto.OAuthAuthorizeVO;
import com.snail.platform.function.auth.dto.OAuthLoginRequest;
import com.snail.platform.function.auth.dto.OAuthPickRequest;
import com.snail.platform.function.auth.dto.SmsCaptchaRequest;
import com.snail.platform.function.auth.dto.SmsLoginRequest;
import com.snail.platform.function.auth.dto.SmsPickRequest;
import com.snail.platform.function.external.api.ExternalCallException;
import com.snail.platform.function.external.api.SmsPort;
import com.snail.platform.function.external.api.SocialAuthPort;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import com.snail.platform.function.system.log.mapper.SysLoginLogMapper;
import com.snail.platform.function.system.support.CacheEvictor;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.entity.SysUserIdentity;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import com.snail.platform.function.system.user.mapper.SysUserIdentityMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 锁定命中时的统一文案 */
    private static final String LOCKED_MESSAGE = "失败次数过多,请稍后再试";

    /** 密码登录方式编码,落登录日志 login_type 列 */
    private static final String LOGIN_TYPE_PASSWORD = "password";

    /** 短信登录方式编码,落登录日志 login_type 列 */
    private static final String LOGIN_TYPE_SMS = "sms";

    /** 手机号形态闸:11 位大陆手机号,不匹配直接拒绝,不进查询不进限流计数 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    /** pick 成功后登录日志里的路径标注 */
    private static final String PICK_NOTE = "(选择租户)";

    /** 第三方登录方式编码,落登录日志 login_type 列,与 V10 迁移预留一致 */
    private static final String LOGIN_TYPE_DINGTALK = "dingtalk";

    private static final String LOGIN_TYPE_FEISHU = "feishu";

    private static final String LOGIN_TYPE_WECOM = "wecom";

    /** 第三方支持的来源与展示顺序:前端入口渲染顺序,登录方式列表按此过滤 */
    private static final List<String> OAUTH_SOURCE_ORDER = List.of("dingtalk", "feishu", "wecom");

    /** state 签发限流键前缀:按 IP 计账 */
    private static final String OAUTH_ISSUE_KEY_PREFIX = "snail:auth:oauth-issue:";

    /** state 签发限流:同 IP 每分钟 20 次,按尝试计账(与短信发送限流同口径,失败不退额度) */
    private static final int OAUTH_ISSUE_LIMIT = 20;

    private static final Duration OAUTH_ISSUE_WINDOW = Duration.ofMinutes(1);

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

    /** 短信验证码的生成/核销/发送限流闸 */
    private final SmsCaptchaService smsCaptchaService;

    /** 多租户账号选择的一次性凭证 */
    private final LoginPickService pickService;

    /** 短信发送端口,厂商细节在 external 网关;无任何实现(mock 关、真实现未接入)时缺席,运行期降级 */
    private final ObjectProvider<SmsPort> smsPortProvider;

    /** 短信登录总开关,灰度/回滚用;false 时短信三端点报"功能未开放" */
    private final boolean smsLoginEnabled;

    /** 阿里云报备通过的验证码模板编码,不硬编码;mock 通道忽略该值 */
    private final String smsLoginTemplateCode;

    /** OAuth state 一次性凭证 */
    private final OAuthStateService oauthStateService;

    /** OAuth 多账号选择凭证 */
    private final OAuthPickService oauthPickService;

    /** 第三方身份绑定读写,豁免表;auth 登录链写绑定,system 用户域只读展示 */
    private final SysUserIdentityMapper userIdentityMapper;

    /** 三家厂商端口,按 source 匹配;dev mock 与生产真实客户端互斥装配 */
    private final List<SocialAuthPort> socialAuthPorts;

    /** 通用计数锁定器:state 签发限流 */
    private final SnailRateGuard rateGuard;

    /** 第三方扫码登录总开关,灰度/回滚用;false 时四端点统一报"登录方式未开放" */
    private final boolean oauthEnabled;

    /** 手机号 JIT 绑定开关:未绑定身份按厂商实名手机号自动关联唯一可用账号并落绑定 */
    private final boolean oauthPhoneBindEnabled;

    /** 各来源授权回调白名单(逗号分隔,精确匹配):authorize 校验,免登态兜底解析 */
    private final List<String> dingtalkRedirectUris;

    private final List<String> feishuRedirectUris;

    private final List<String> wecomRedirectUris;

    public AuthService(SysUserMapper userMapper, SysTenantMapper tenantMapper, SysLoginLogMapper loginLogMapper,
                       CaptchaService captchaService, LoginFailGuard failGuard, JwtTokenService jwtTokenService,
                       CacheEvictor cacheEvictor, SmsCaptchaService smsCaptchaService,
                       LoginPickService pickService, ObjectProvider<SmsPort> smsPortProvider,
                       OAuthStateService oauthStateService, OAuthPickService oauthPickService,
                       SysUserIdentityMapper userIdentityMapper, List<SocialAuthPort> socialAuthPorts,
                       SnailRateGuard rateGuard,
                       @Value("${snail.auth.sms-login.enabled:true}") boolean smsLoginEnabled,
                       @Value("${snail.external.sms.login-template-code:}") String smsLoginTemplateCode,
                       @Value("${snail.auth.oauth.enabled:true}") boolean oauthEnabled,
                       @Value("${snail.auth.oauth.phone-bind.enabled:true}") boolean oauthPhoneBindEnabled,
                       @Value("${snail.auth.oauth.sources.dingtalk.redirect-uris:}") String dingtalkRedirectUris,
                       @Value("${snail.auth.oauth.sources.feishu.redirect-uris:}") String feishuRedirectUris,
                       @Value("${snail.auth.oauth.sources.wecom.redirect-uris:}") String wecomRedirectUris) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
        this.loginLogMapper = loginLogMapper;
        this.captchaService = captchaService;
        this.failGuard = failGuard;
        this.jwtTokenService = jwtTokenService;
        this.cacheEvictor = cacheEvictor;
        this.smsCaptchaService = smsCaptchaService;
        this.pickService = pickService;
        this.smsPortProvider = smsPortProvider;
        this.oauthStateService = oauthStateService;
        this.oauthPickService = oauthPickService;
        this.userIdentityMapper = userIdentityMapper;
        this.socialAuthPorts = socialAuthPorts;
        this.rateGuard = rateGuard;
        this.smsLoginEnabled = smsLoginEnabled;
        this.smsLoginTemplateCode = smsLoginTemplateCode;
        this.oauthEnabled = oauthEnabled;
        this.oauthPhoneBindEnabled = oauthPhoneBindEnabled;
        this.dingtalkRedirectUris = parseRedirectUris(dingtalkRedirectUris);
        this.feishuRedirectUris = parseRedirectUris(feishuRedirectUris);
        this.wecomRedirectUris = parseRedirectUris(wecomRedirectUris);
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
            writeLoginLog(http, 0L, username, null, 0, LOCKED_MESSAGE, LOGIN_TYPE_PASSWORD);
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
            writeLoginLog(http, user.getTenantId(), username, user.getUserId(), 1, "登录成功", LOGIN_TYPE_PASSWORD);
            return new LoginSuccessVO(token, "Bearer", jwtTokenService.expiresInSeconds(),
                    user.getUserId(), user.getUsername(), user.getNickname(),
                    user.getTenantId(), tenant.getName(),
                    user.getPasswordModifyTime() == null);
        } catch (BizException e) {
            // 登录在租户上下文建立之前就被拒(验证码/查无此人/锁定),此处只有查到的用户能给出租户;
            // 查不到的一律记 0=归属不明,这批行只有持跨租户码的平台侧看得见。
            // 登录日志保留细粒度原因(租户停用/到期、账号停用)供管理员排查;
            // 对外的准入类拒绝统一口径为"账号或密码错误"防账号枚举——验证码已过即可探测状态差异
            Long tenantId = user != null ? user.getTenantId() : 0L;
            Long userId = user != null ? user.getUserId() : null;
            writeLoginLog(http, tenantId, username, userId, 0, e.getMessage(), LOGIN_TYPE_PASSWORD);
            if (e instanceof LoginRejects.Credential) {
                failGuard.increaseCredentialFail(ip, username);
                throw e;
            }
            throw new LoginRejects.Access("账号或密码错误");
        }
    }

    /**
     * 发送登录短信验证码:人机闸(复用图形验证码)→ 三道限流闸(按尝试计账,失败不退额度)→
     * 定位(存在才发,空集直报"账号不存在")→ 通道可用性 → 生成并登记验证码 → 经网关发送。
     * 码值只在发送参数里出现一次,任何日志不输出;网关侧留痕(手机号打码)由 SmsPort 实现 负责。
     */
    public void sendSmsCaptcha(SmsCaptchaRequest request, HttpServletRequest http) {
        requireSmsLoginEnabled();
        String phone = request.phone().trim();
        String ip = JakartaServletUtil.getClientIP(http);
        captchaService.verify(ip, request.captchaKey(), request.captchaCode());
        smsCaptchaService.checkSendQuota(ip, phone);
        List<SysUser> hits = usersByPhone(phone);
        if (hits.isEmpty()) {
            throw BizException.badRequest("账号不存在");
        }
        // 缺席降级与邮件通道同口径:实现缺席(mock 关/真实现未接入)与 available()=false 同文案,
        // 端点可用但发送被拒,其余功能不受影响
        SmsPort smsPort = smsPortProvider.getIfAvailable();
        if (smsPort == null || !smsPort.available()) {
            throw BizException.badRequest("短信通道未配置");
        }
        String code = smsCaptchaService.issue(phone);
        try {
            smsPort.send(new SmsPort.SmsCommand(phone, smsLoginTemplateCode, Map.of("code", code),
                    "auth", "login-captcha", null));
        } catch (ExternalCallException e) {
            // 网关侧已留失败流水;对调用方只给统一文案,不透传厂商错因
            throw BizException.badRequest("短信发送失败,请稍后再试");
        }
    }

    /**
     * 短信验证码登录:锁定(IP+手机号)→ 短信码 GETDEL 核销 → 按 phone 跨租户定位(旁路窗口)→
     * 单命中走状态检查后签发;多命中做状态预筛(停用/到期候选静默剔除)后发 pickKey 候选列表。
     * 验证码在本次调用即核销——多账号选择是"选门",不是再次验证。
     * 失败分类与密码链同构:凭证类计 IP+手机号锁定,准入类不计;对外统一"账号或密码错误"防状态探测。
     */
    public Object loginBySms(SmsLoginRequest request, HttpServletRequest http) {
        requireSmsLoginEnabled();
        String phone = request.phone().trim();
        String ip = JakartaServletUtil.getClientIP(http);
        if (failGuard.isCredentialLocked(ip, phone)) {
            writeLoginLog(http, 0L, phone, null, 0, LOCKED_MESSAGE, LOGIN_TYPE_SMS);
            throw BizException.badRequest(LOCKED_MESSAGE);
        }
        SysUser located = null;
        try {
            smsCaptchaService.verify(ip, phone, request.smsCode());
            List<SysUser> hits = usersByPhone(phone);
            if (hits.isEmpty()) {
                throw new LoginRejects.Credential("账号不存在");
            }
            if (hits.size() == 1) {
                SysUser user = hits.get(0);
                located = user;
                SysTenant tenant = requireAccessible(user);
                return issueSmsToken(user, tenant, ip, phone, http, "");
            }
            // 多命中:候选按 user_id 升序(hits 已排序);停用/到期的候选静默剔除,全部被剔按统一口径拒
            List<SysUser> candidates = hits.stream()
                    .filter(user -> {
                        try {
                            requireAccessible(user);
                            return true;
                        } catch (BizException rejected) {
                            return false;
                        }
                    })
                    .toList();
            if (candidates.isEmpty()) {
                throw new LoginRejects.Access("账号或密码错误");
            }
            String pickKey = pickService.issue(candidates.stream().map(SysUser::getUserId).toList());
            return new LoginAccountsVO(pickKey, accountsOf(candidates));
        } catch (BizException e) {
            // 定位失败记 0=归属不明;命中后拒绝的行带出租户与用户,日志留细粒度原因
            Long tenantId = located != null ? located.getTenantId() : 0L;
            Long userId = located != null ? located.getUserId() : null;
            writeLoginLog(http, tenantId, phone, userId, 0, e.getMessage(), LOGIN_TYPE_SMS);
            if (e instanceof LoginRejects.Credential) {
                failGuard.increaseCredentialFail(ip, phone);
                throw e;
            }
            throw new LoginRejects.Access("账号或密码错误");
        }
    }

    /**
     * 多租户账号选择换令牌:pickKey GETDEL 一次一用,userId 必须在候选集内;
     * 选择等待期间状态可能变化,签发前复检租户/用户状态——复检失败与密码链同口径拒绝。
     * pickKey 一次性 + 高熵已防穷举,失败不再叠加计数。
     */
    public LoginSuccessVO pickSmsAccount(SmsPickRequest request, HttpServletRequest http) {
        requireSmsLoginEnabled();
        SysUser chosen = null;
        try {
            List<Long> candidates = pickService.consume(request.pickKey().trim());
            if (candidates == null || !candidates.contains(request.userId())) {
                throw BizException.badRequest("选择已过期,请重新登录");
            }
            chosen = TenantContextHolder.withoutTenant(() -> userMapper.selectById(request.userId()));
            if (chosen == null) {
                throw BizException.badRequest("选择已过期,请重新登录");
            }
            SysTenant tenant = requireAccessible(chosen);
            return issueSmsToken(chosen, tenant, JakartaServletUtil.getClientIP(http),
                    chosen.getPhone(), http, PICK_NOTE);
        } catch (BizException e) {
            Long tenantId = chosen != null ? chosen.getTenantId() : 0L;
            writeLoginLog(http, tenantId, chosen != null ? chosen.getPhone() : "unknown",
                    chosen != null ? chosen.getUserId() : null, 0, e.getMessage(), LOGIN_TYPE_SMS);
            throw e instanceof LoginRejects.Credential credential
                    ? credential : new LoginRejects.Access("账号或密码错误");
        }
    }

    /**
     * 租户/用户状态闸:租户行缺失与显式停用同口径报"租户已停用",到期另报;停用账号报"账号已停用"。
     * 通过则返回租户行(签发 VO 要租户名);具体原因只进登录日志,对外由调用方统一文案。
     */
    private SysTenant requireAccessible(SysUser user) {
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
        return tenant;
    }

    /** 签发短信登录令牌并落成功日志;计数清零键是"来源+手机号",与失败计数同一把尺 */
    private LoginSuccessVO issueSmsToken(SysUser user, SysTenant tenant, String ip,
                                         String phone, HttpServletRequest http, String note) {
        failGuard.clearCredentialFails(ip, phone);
        return issueToken(user, tenant, http, phone, "短信登录成功", LOGIN_TYPE_SMS, note);
    }

    /**
     * 签发登录令牌并落成功日志的公共收尾(密码/短信/第三方三链同一形态):
     * 令牌 claims 取用户当前版本供过滤器比对,成功日志带登录方式编码,VO 组装式与既有链逐字一致。
     * 计数清零由调用方按各自键(用户名/手机号/openId)先行——清零键必须与失败计数同一把尺。
     */
    private LoginSuccessVO issueToken(SysUser user, SysTenant tenant, HttpServletRequest http,
                                      String logUsername, String msg, String loginType, String note) {
        String token = jwtTokenService.issue(
                new SnailPrincipal(user.getUserId(), user.getTenantId(), user.getUsername(),
                        user.getOrgId(), user.getTokenVersion()));
        writeLoginLog(http, user.getTenantId(), logUsername, user.getUserId(), 1,
                msg + (note == null ? "" : note), loginType);
        return new LoginSuccessVO(token, "Bearer", jwtTokenService.expiresInSeconds(),
                user.getUserId(), user.getUsername(), user.getNickname(),
                user.getTenantId(), tenant.getName(),
                user.getPasswordModifyTime() == null);
    }

    // ==================== 第三方扫码登录(钉钉/飞书/企业微信) ====================

    /**
     * 第三方扫码入口列表:总开关开启且来源凭证齐备且回调白名单已登记的来源,按固定顺序返回;
     * 空列表即前端隐藏第三方入口行。与短信开关同口径:关闭是统一拒绝,不泄露差异。
     */
    public List<String> oauthOptions() {
        if (!oauthEnabled) {
            return List.of();
        }
        return OAUTH_SOURCE_ORDER.stream().filter(this::oauthSourceReady).toList();
    }

    /**
     * 签发第三方授权跳转:来源可用 → 回调白名单精确匹配 → state 登记(5 分钟 GETDEL)
     * → 拼厂商授权地址。state 签发按 IP 限流(同分钟 20 次,按尝试计账),防状态键刷量。
     */
    public OAuthAuthorizeVO oauthAuthorize(String source, String redirectUri, HttpServletRequest http) {
        SocialAuthPort port = requireOauthSource(source);
        String trimmed = redirectUri == null ? "" : redirectUri.trim();
        if (!redirectUrisOf(source).contains(trimmed)) {
            throw BizException.badRequest("回调地址未登记");
        }
        long count = rateGuard.record(OAUTH_ISSUE_KEY_PREFIX + JakartaServletUtil.getClientIP(http),
                OAUTH_ISSUE_WINDOW);
        if (count > OAUTH_ISSUE_LIMIT) {
            throw BizException.badRequest("请求过于频繁,请稍后再试");
        }
        String state = oauthStateService.issue(source, trimmed);
        return new OAuthAuthorizeVO(port.authorizeUrl(trimmed, state), state);
    }

    /**
     * 第三方授权码登录:state 一次性校验 → 厂商交换身份(external 网关,异常统一转译)
     * → 绑定定位(豁免表,跨租户)→ 命中即签发;未绑定且开关开启时按厂商实名手机号 JIT 关联。
     * 多命中走状态预筛后发 pickKey 候选列表(与短信链同构,身份验证在首调,pick 只是"选门")。
     * 失败分类与密码/短信链同构:凭证类(openId 已知)计 IP+openId 锁定,准入类不计数;
     * 厂商交换失败与 state 失效不计数;锁定检查在交换成功后(openId 此刻才可知)。
     * 对外文案口径见方案 §5.2:绑定未命中给引导文案(与短信链"账号不存在"同级),状态类统一防探测。
     */
    public Object loginByOauth(OAuthLoginRequest request, HttpServletRequest http) {
        String ip = JakartaServletUtil.getClientIP(http);
        SocialAuthPort port = requireOauthSource(request.source());
        SysUser located = null;
        String openId = null;
        try {
            // state 核验:重定向流必带,GETDEL 原子核销;免登态(无授权跳转,二期)无 state,回调地址走兜底解析
            String stateRedirectUri = null;
            if (notBlank(request.state())) {
                stateRedirectUri = oauthStateService.consume(request.state().trim(), request.source());
                if (stateRedirectUri == null) {
                    throw BizException.badRequest("登录已过期,请重新发起登录");
                }
                if (notBlank(request.redirectUri()) && !request.redirectUri().trim().equals(stateRedirectUri)) {
                    throw BizException.badRequest("登录已过期,请重新发起登录");
                }
            }
            String exchangeRedirectUri = stateRedirectUri != null ? stateRedirectUri
                    : (notBlank(request.redirectUri()) ? request.redirectUri().trim()
                    : firstRedirectUri(request.source()));
            SocialAuthPort.SocialIdentity identity = port.exchange(request.code().trim(), exchangeRedirectUri);
            if (identity == null || !notBlank(identity.openId())) {
                throw new ExternalCallException(port.source(), "oauth-exchange", "平台未返回用户标识");
            }
            openId = identity.openId();
            // 锁定检查在交换成功后:openId 此刻才可知;交换失败不产生计数(没有可锁定的身份)
            if (failGuard.isCredentialLocked(ip, openId)) {
                writeLoginLog(http, 0L, openId, null, 0, LOCKED_MESSAGE, request.source());
                throw BizException.badRequest(LOCKED_MESSAGE);
            }

            // 绑定定位:表在豁免清单,查询本就跨租户;按 user_id 升序与短信链同口径
            List<SysUserIdentity> bindings = userIdentityMapper.selectList(
                    Wrappers.<SysUserIdentity>lambdaQuery()
                            .eq(SysUserIdentity::getSource, request.source())
                            .eq(SysUserIdentity::getOpenId, openId)
                            .orderByAsc(SysUserIdentity::getUserId));
            List<SysUser> candidates = bindings.isEmpty() ? List.of()
                    : accessibleCandidates(loadUsers(bindings.stream().map(SysUserIdentity::getUserId).toList()));
            if (candidates.size() == 1) {
                located = candidates.get(0);
                SysTenant tenant = requireAccessible(located);
                touchBinding(request.source(), openId, identity.nickname(), located);
                failGuard.clearCredentialFails(ip, openId);
                return issueToken(located, tenant, http, openId,
                        sourceLabel(request.source()) + "登录成功", request.source(), "");
            }
            if (candidates.size() > 1) {
                String pickKey = oauthPickService.issue(request.source(), openId,
                        candidates.stream().map(SysUser::getUserId).toList());
                return new LoginAccountsVO(pickKey, accountsOf(candidates));
            }

            // 无绑定:按厂商实名手机号 JIT 关联唯一可用账号并落绑定;开关关闭或厂商未返回手机号则引导
            if (!oauthPhoneBindEnabled || !notBlank(identity.mobile())) {
                throw new LoginRejects.Credential("未找到关联账号,请使用账号密码登录或联系管理员维护手机号");
            }
            List<SysUser> usable = accessibleCandidates(usersByPhone(identity.mobile()));
            if (usable.isEmpty()) {
                throw new LoginRejects.Credential("未找到关联账号,请使用账号密码登录或联系管理员维护手机号");
            }
            if (usable.size() == 1) {
                located = usable.get(0);
                SysTenant tenant = requireAccessible(located);
                saveBinding(request.source(), openId, identity.unionId(), identity.nickname(), located);
                failGuard.clearCredentialFails(ip, openId);
                return issueToken(located, tenant, http, openId,
                        sourceLabel(request.source()) + "登录成功", request.source(), "");
            }
            String pickKey = oauthPickService.issue(request.source(), openId,
                    usable.stream().map(SysUser::getUserId).toList());
            return new LoginAccountsVO(pickKey, accountsOf(usable));
        } catch (ExternalCallException e) {
            // 厂商交换失败:网关侧已留失败流水;对调用方统一文案,不透传厂商错因,不进锁定计数
            writeLoginLog(http, 0L, "unknown", null, 0, e.getMessage(), request.source());
            throw BizException.badRequest("第三方登录失败,请稍后再试");
        } catch (BizException e) {
            // 定位失败记 0=归属不明;命中后拒绝的行带出租户与用户,日志留细粒度原因
            Long tenantId = located != null ? located.getTenantId() : 0L;
            Long userId = located != null ? located.getUserId() : null;
            writeLoginLog(http, tenantId, openId != null ? openId : "unknown", userId, 0, e.getMessage(),
                    request.source());
            if (e instanceof LoginRejects.Credential credential) {
                if (openId != null) {
                    failGuard.increaseCredentialFail(ip, openId);
                }
                throw credential;
            }
            if (e instanceof LoginRejects.Access) {
                throw new LoginRejects.Access("账号或密码错误");
            }
            throw e;
        }
    }

    /**
     * 多账号选择换令牌:pickKey GETDEL 一次一用,userId 必须在候选集内;选择等待期间状态可能变化,
     * 签发前复检租户/用户状态。手机号路径的候选此时落绑定(绑定路径候选已存在,唯一键兜底并发)。
     * pickKey 高熵一次性已防穷举,失败不再叠加计数;pickKey 失效时连来源都不可知,不落登录日志。
     */
    public LoginSuccessVO pickOauthAccount(OAuthPickRequest request, HttpServletRequest http) {
        OAuthPickService.PickCandidate candidate = oauthPickService.consume(request.pickKey().trim());
        if (candidate == null || !candidate.userIds().contains(request.userId())) {
            throw BizException.badRequest("选择已过期,请重新登录");
        }
        SysUser chosen = null;
        try {
            chosen = TenantContextHolder.withoutTenant(() -> userMapper.selectById(request.userId()));
            if (chosen == null) {
                throw BizException.badRequest("选择已过期,请重新登录");
            }
            SysTenant tenant = requireAccessible(chosen);
            saveBinding(candidate.source(), candidate.openId(), null, null, chosen);
            failGuard.clearCredentialFails(JakartaServletUtil.getClientIP(http), candidate.openId());
            return issueToken(chosen, tenant, http, candidate.openId(),
                    sourceLabel(candidate.source()) + "登录成功", candidate.source(), PICK_NOTE);
        } catch (BizException e) {
            Long tenantId = chosen != null ? chosen.getTenantId() : 0L;
            writeLoginLog(http, tenantId, candidate.openId(), chosen != null ? chosen.getUserId() : null,
                    0, e.getMessage(), candidate.source());
            throw e instanceof LoginRejects.Credential credential
                    ? credential : new LoginRejects.Access("账号或密码错误");
        }
    }

    /** 来源是否可用:端口凭证与回调白名单两者齐备(总开关已由调用方判过) */
    private boolean oauthSourceReady(String source) {
        SocialAuthPort port = portOf(source);
        return port != null && port.available() && !redirectUrisOf(source).isEmpty();
    }

    /** 按 source 匹配端口;dev mock 与生产真实客户端互斥装配,同名只会命中一个 */
    private SocialAuthPort portOf(String source) {
        return socialAuthPorts.stream()
                .filter(port -> port.source().equals(source))
                .findFirst()
                .orElse(null);
    }

    /** 端口与开关闸:任一不满足统一"登录方式未开放",不泄露是开关还是凭证的哪种缺失 */
    private SocialAuthPort requireOauthSource(String source) {
        if (!oauthEnabled) {
            throw BizException.badRequest("登录方式未开放");
        }
        SocialAuthPort port = portOf(source);
        if (port == null || !port.available()) {
            throw BizException.badRequest("登录方式未开放");
        }
        return port;
    }

    /** 来源回调白名单;未知来源返回空集(authorize 与免登兜底解析都过不去) */
    private List<String> redirectUrisOf(String source) {
        return switch (source) {
            case LOGIN_TYPE_DINGTALK -> dingtalkRedirectUris;
            case LOGIN_TYPE_FEISHU -> feishuRedirectUris;
            case LOGIN_TYPE_WECOM -> wecomRedirectUris;
            default -> List.of();
        };
    }

    /** 免登态(无授权跳转)的交换地址兜底:该来源白名单第一项;未登记即 null,由实现按缺失抛错 */
    private String firstRedirectUri(String source) {
        List<String> uris = redirectUrisOf(source);
        return uris.isEmpty() ? null : uris.get(0);
    }

    /** 状态预筛:租户停用/到期、账号停用的候选静默剔除(与短信链同口径),全部被剔按统一文案拒 */
    private List<SysUser> accessibleCandidates(List<SysUser> hits) {
        return hits.stream()
                .filter(user -> {
                    try {
                        requireAccessible(user);
                        return true;
                    } catch (BizException rejected) {
                        return false;
                    }
                })
                .toList();
    }

    /** 按绑定行批量取用户(旁路窗口只覆盖这一条查询),按 user_id 升序,次序稳定 */
    private List<SysUser> loadUsers(List<Long> userIds) {
        List<SysUser> users = TenantContextHolder.withoutTenant(() -> userMapper.selectBatchIds(userIds));
        return users.stream()
                .sorted(java.util.Comparator.comparing(SysUser::getUserId))
                .toList();
    }

    /** 命中既有绑定:刷最近登录时间与昵称快照;单行写自提交即原子,免登代理不进事务 */
    private void touchBinding(String source, String openId, String nickname, SysUser user) {
        userIdentityMapper.update(null, Wrappers.<SysUserIdentity>lambdaUpdate()
                .set(SysUserIdentity::getLastLoginTime, LocalDateTime.now())
                .set(nickname != null, SysUserIdentity::getNickname, nickname)
                .eq(SysUserIdentity::getSource, source)
                .eq(SysUserIdentity::getOpenId, openId)
                .eq(SysUserIdentity::getUserId, user.getUserId()));
    }

    /** 首绑落行:唯一键挡并发重复,撞键视为已绑定继续(用户无感);单行写自提交即原子 */
    private void saveBinding(String source, String openId, String unionId, String nickname, SysUser user) {
        SysUserIdentity row = new SysUserIdentity();
        row.setUserId(user.getUserId());
        row.setTenantId(user.getTenantId());
        row.setSource(source);
        row.setOpenId(openId);
        row.setUnionId(unionId);
        row.setNickname(nickname);
        try {
            userIdentityMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException duplicated) {
            log.debug("第三方身份绑定并发重复,视为已绑定: source={} openId={}", source, openId);
        }
    }

    /** 来源编码到中文名,登录日志 msg 用 */
    private static String sourceLabel(String source) {
        return switch (source) {
            case LOGIN_TYPE_DINGTALK -> "钉钉";
            case LOGIN_TYPE_FEISHU -> "飞书";
            case LOGIN_TYPE_WECOM -> "企业微信";
            default -> source;
        };
    }

    /** 空白判定:state/redirectUri/mobile 等"可空但有语义"的字符串入参统一用它 */
    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 回调白名单解析:逗号分隔,去空白,精确匹配 */
    private static List<String> parseRedirectUris(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .toList();
    }

    /** 按 phone 跨租户定位(登录时没有租户上下文,旁路窗口只覆盖这一条查询);user_id 升序,次序稳定 */
    private List<SysUser> usersByPhone(String phone) {
        return TenantContextHolder.withoutTenant(() -> userMapper.selectList(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getPhone, phone)
                        .orderByAsc(SysUser::getUserId)));
    }

    /** 短信登录总开关:灰度/回滚用,关闭时三端点同文案,不泄露功能存在性之外的任何差异 */
    private void requireSmsLoginEnabled() {
        if (!smsLoginEnabled) {
            throw BizException.badRequest("功能未开放");
        }
    }

    /** 多命中的候选投影:批量回查租户名(sys_tenant 是 ignore 表,按 ID 定点取),最小暴露面 */
    private List<LoginAccountsVO.Account> accountsOf(List<SysUser> candidates) {
        List<Long> tenantIds = candidates.stream().map(SysUser::getTenantId).distinct().toList();
        Map<Long, String> tenantNames = tenantMapper.selectBatchIds(tenantIds).stream()
                .collect(java.util.stream.Collectors.toMap(SysTenant::getTenantId, SysTenant::getName));
        return candidates.stream()
                .map(user -> new LoginAccountsVO.Account(user.getUserId(), user.getTenantId(),
                        tenantNames.get(user.getTenantId()), user.getUsername(), user.getNickname()))
                .toList();
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
        // 租户上下文缺失时跳过点清:拼出 "null:userId" 键只会清到不存在的键,不如交由 TTL 兜底
        Long tenantId = TenantContextHolder.get();
        if (tenantId != null) {
            cacheEvictor.evict("userSession", CacheEvictor.tenantKey(tenantId, userId));
        }
    }

    /** 留痕写失败不许反过来影响登录响应——库都挂了的场景没有一致性问题可言 */
    private void writeLoginLog(HttpServletRequest http, Long tenantId, String username,
                               Long userId, int status, String msg, String loginType) {
        UserAgent ua = UserAgentUtil.parse(http.getHeader("User-Agent"));
        SysLoginLog row = new SysLoginLog();
        row.setTenantId(tenantId);
        row.setUsername(username);
        row.setUserId(userId);
        row.setIp(JakartaServletUtil.getClientIP(http));
        row.setBrowser(ua == null ? null : ua.getBrowser().getName());
        row.setOs(ua == null ? null : ua.getOs().getName());
        row.setLoginType(loginType);
        row.setStatus(status);
        row.setMsg(msg);
        row.setLoginTime(LocalDateTime.now());
        try {
            loginLogMapper.insert(row);
        } catch (RuntimeException e) {
            // 留痕失败不阻断登录响应,但至少留一条 debug 便于发现日志链路故障
            log.debug("登录日志写入失败: username={}", username, e);
        }
    }
}
