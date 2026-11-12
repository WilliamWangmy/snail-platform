package com.snail.platform.function.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.hutool.crypto.digest.BCrypt;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.redis.SnailRateGuard;
import com.snail.platform.framework.security.token.JwtTokenService;
import com.snail.platform.function.auth.SnailAuthProperties;
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.external.api.SmsPort;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import com.snail.platform.function.system.log.mapper.SysLoginLogMapper;
import com.snail.platform.function.system.support.CacheEvictor;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserIdentityMapper;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 职责:登录拒绝文案分类的离线回归——验证码失败必须回自己的文案,凭证/准入类仍统一回"账号或密码错误"。
 * scene 闸用例(租户管理增强,2026-11-12 改口径):scene 闸挪到密码比对之后,口令验真才报
 * 「租户未开通{端名}权限,请联系平台管理员」(文案透传,不计失败数);口令错仍统一文案防探测。
 * 不启 Spring 上下文、不连 MySQL/Valkey:所有出口都是 Mockito 桩,验证码失败用与 CaptchaService
 * 同类型的 LoginRejects.Captcha 直接构造(包内可见,故本测试与 service 同包)。
 * 上游:AuthService.login 的 catch(BizException) 分流;下游:无外设断言。
 */
class LoginRejectMessageTest {

    private CaptchaService captchaService;
    private LoginFailGuard failGuard;
    private SysUserMapper userMapper;
    private SysTenantMapper tenantMapper;
    private SysLoginLogMapper loginLogMapper;
    private AuthService authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        captchaService = mock(CaptchaService.class);
        failGuard = mock(LoginFailGuard.class);
        userMapper = mock(SysUserMapper.class);
        tenantMapper = mock(SysTenantMapper.class);
        loginLogMapper = mock(SysLoginLogMapper.class);
        ObjectProvider<SmsPort> smsPorts = mock(ObjectProvider.class);
        authService = new AuthService(
                userMapper, tenantMapper, loginLogMapper,
                captchaService, failGuard, mock(JwtTokenService.class),
                mock(CacheEvictor.class), mock(SmsCaptchaService.class),
                mock(LoginPickService.class), smsPorts,
                mock(OAuthStateService.class), mock(OAuthPickService.class),
                mock(SysUserIdentityMapper.class), List.of(),
                mock(SnailRateGuard.class),
                // 统计键写入面:mock 桩,opsForSet 返回 null 触发的 NPE 由 writeLoginLog 内 try/catch 吞掉
                mock(StringRedisTemplate.class),
                // 配置类用缺省实例:开关全 true、模板编码与白名单为空,与原散参 true,"",true,true,"","","" 等价
                new SnailAuthProperties());
    }

    /** 验证码四种失败都要原样回给用户,且发生在定位用户之前、不进账号锁定桶 */
    @ParameterizedTest
    @ValueSource(strings = {
            "请输入验证码",
            "验证码已过期,请重新获取",
            "验证码错误",
            "验证码错误次数过多,请稍后再试"
    })
    void captchaFailureKeepsItsOwnMessage(String message) {
        doThrow(new LoginRejects.Captcha(message)).when(captchaService).verify(any(), any(), any());

        BizException error = assertThrows(BizException.class, () -> authService.login(request(), http()));

        assertEquals(message, error.getMessage());
        verifyNoInteractions(userMapper);
        verify(failGuard, never()).increaseCredentialFail(any(), any());
    }

    /** 查无此人仍回统一文案防账号枚举,且照旧累加凭证失败计数 */
    @Test
    void unknownUserStillUnifiedAndCounted() {
        BizException error = assertThrows(BizException.class, () -> authService.login(request(), http()));

        assertEquals("账号或密码错误", error.getMessage());
        verify(failGuard).increaseCredentialFail(any(), any());
    }

    /** 口令错误同上:验证码放行后走到 BCrypt 比对,对外仍不能区分是账号不存在还是口令错 */
    @Test
    void wrongPasswordStillUnifiedAndCounted() {
        SysUser user = userWithStatus(0);
        user.setPassword(BCrypt.hashpw("right-password", BCrypt.gensalt()));
        when(userMapper.selectOne(any())).thenReturn(user);
        when(tenantMapper.selectById(1L)).thenReturn(activeTenant());

        BizException error = assertThrows(BizException.class, () -> authService.login(request(), http()));

        assertEquals("账号或密码错误", error.getMessage());
        verify(failGuard).increaseCredentialFail(any(), any());
    }

    /** 用户定位后的准入类拒绝仍统一成凭证文案,细粒度原因只留在登录日志里 */
    @Test
    void disabledUserStillUnifiedAndNotCounted() {
        when(userMapper.selectOne(any())).thenReturn(userWithStatus(1));
        when(tenantMapper.selectById(1L)).thenReturn(activeTenant());

        BizException error = assertThrows(BizException.class, () -> authService.login(request(), http()));

        assertEquals("账号或密码错误", error.getMessage());
        verify(failGuard, never()).increaseCredentialFail(any(), any());
    }

    /** scene 闸(未开通端):scene 闸在密码比对之后,口令验真才报「租户未开通{端名}权限,请联系平台管理员」
     *  (2026-11-12 拍板改口径,文案透传),不计失败数;同文案照落登录日志(AC-2 日志侧断言) */
    @Test
    void closedSceneTellsTenantToContactPlatformAdmin() {
        SysUser user = userWithStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);
        SysTenant tenant = activeTenant();
        tenant.setWorkflowEnabled(0);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        BizException error = assertThrows(BizException.class,
                () -> authService.login(requestWithPassword("workflow", "right-password"), http()));

        assertEquals("租户未开通流程系统权限,请联系平台管理员", error.getMessage());
        verify(failGuard, never()).increaseCredentialFail(any(), any());
        ArgumentCaptor<SysLoginLog> logCaptor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        assertEquals("租户未开通流程系统权限,请联系平台管理员", logCaptor.getValue().getMsg());
        assertEquals("workflow", logCaptor.getValue().getScene());
        assertEquals(0, logCaptor.getValue().getStatus());
    }

    /** scene 闸(未开通端+口令错):口令比对在 scene 闸之前,口令错仍统一文案防账号探测 */
    @Test
    void wrongPasswordStillUnifiedEvenWhenSceneClosed() {
        SysUser user = userWithStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);
        SysTenant tenant = activeTenant();
        tenant.setWorkflowEnabled(0);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        BizException error = assertThrows(BizException.class,
                () -> authService.login(requestWithPassword("workflow", "wrong-password"), http()));

        assertEquals("账号或密码错误", error.getMessage());
        verify(failGuard).increaseCredentialFail(any(), any());
    }

    /** scene 闸(已开通端):放行到密码比对,口令错仍统一文案并计凭证失败——开通与未开通对外无差异文案 */
    @Test
    void openedScenePassesGateThenPasswordCheck() {
        SysUser user = userWithStatus(0);
        user.setPassword(BCrypt.hashpw("right-password", BCrypt.gensalt()));
        when(userMapper.selectOne(any())).thenReturn(user);
        SysTenant tenant = activeTenant();
        tenant.setWorkflowEnabled(1);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        BizException error = assertThrows(BizException.class,
                () -> authService.login(request("workflow"), http()));

        assertEquals("账号或密码错误", error.getMessage());
        verify(failGuard).increaseCredentialFail(any(), any());
    }

    /** scene 缺省归一:不带 scene 的旧调用方按 admin(管理系统)闸校验,开关未开同样被拒(契约 §5.1) */
    @Test
    void defaultSceneIsAdminAndGated() {
        SysUser user = userWithStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);
        SysTenant tenant = activeTenant();
        tenant.setAdminEnabled(0);
        when(tenantMapper.selectById(1L)).thenReturn(tenant);

        BizException error = assertThrows(BizException.class,
                () -> authService.login(requestWithPassword(null, "right-password"), http()));

        assertEquals("租户未开通管理系统权限,请联系平台管理员", error.getMessage());
        ArgumentCaptor<SysLoginLog> logCaptor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(loginLogMapper).insert(logCaptor.capture());
        assertEquals("租户未开通管理系统权限,请联系平台管理员", logCaptor.getValue().getMsg());
        assertEquals("admin", logCaptor.getValue().getScene());
    }

    private static LoginRequest request() {
        return new LoginRequest("admin", "wrong-password", "captcha-key", "ABCD", null);
    }

    private static LoginRequest request(String scene) {
        return new LoginRequest("admin", "wrong-password", "captcha-key", "ABCD", scene);
    }

    private static LoginRequest requestWithPassword(String scene, String password) {
        return new LoginRequest("admin", password, "captcha-key", "ABCD", scene);
    }

    private static SysUser userWithStatus(Integer status) {
        SysUser user = new SysUser();
        user.setUserId(7L);
        user.setTenantId(1L);
        user.setUsername("admin");
        user.setStatus(status);
        user.setPassword(BCrypt.hashpw("right-password", BCrypt.gensalt()));
        return user;
    }

    private static SysTenant activeTenant() {
        SysTenant tenant = new SysTenant();
        tenant.setTenantId(1L);
        tenant.setStatus(0);
        // 五开关显式全开:本测试只验证密码/文案/计数链路,默认租户与种子口径一致(全 1),
        // 不设值(null)会被 scene 闸按"未开通"拦下(scene 闸在密码比对之后,凭证计数分支就走不到了)
        tenant.setAdminEnabled(1);
        tenant.setWorkflowEnabled(1);
        tenant.setMobileEnabled(1);
        tenant.setDashboardEnabled(1);
        tenant.setPortalEnabled(1);
        return tenant;
    }

    /** User-Agent 给一个可解析值:写登录日志时会解析浏览器/OS,空头在部分 Hutool 版本下会抛 NPE */
    private static MockHttpServletRequest http() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        return http;
    }
}
