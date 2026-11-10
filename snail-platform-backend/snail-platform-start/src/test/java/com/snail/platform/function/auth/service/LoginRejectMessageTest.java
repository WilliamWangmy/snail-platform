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
import com.snail.platform.function.auth.dto.LoginRequest;
import com.snail.platform.function.external.api.SmsPort;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 职责:登录拒绝文案分类的离线回归——验证码失败必须回自己的文案,凭证/准入类仍统一回"账号或密码错误"。
 * 不启 Spring 上下文、不连 MySQL/Valkey:所有出口都是 Mockito 桩,验证码失败用与 CaptchaService
 * 同类型的 LoginRejects.Captcha 直接构造(包内可见,故本测试与 service 同包)。
 * 上游:AuthService.login 的 catch(BizException) 分流;下游:无外设断言。
 */
class LoginRejectMessageTest {

    private CaptchaService captchaService;
    private LoginFailGuard failGuard;
    private SysUserMapper userMapper;
    private SysTenantMapper tenantMapper;
    private AuthService authService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        captchaService = mock(CaptchaService.class);
        failGuard = mock(LoginFailGuard.class);
        userMapper = mock(SysUserMapper.class);
        tenantMapper = mock(SysTenantMapper.class);
        ObjectProvider<SmsPort> smsPorts = mock(ObjectProvider.class);
        authService = new AuthService(
                userMapper, tenantMapper, mock(SysLoginLogMapper.class),
                captchaService, failGuard, mock(JwtTokenService.class),
                mock(CacheEvictor.class), mock(SmsCaptchaService.class),
                mock(LoginPickService.class), smsPorts,
                mock(OAuthStateService.class), mock(OAuthPickService.class),
                mock(SysUserIdentityMapper.class), List.of(),
                mock(SnailRateGuard.class),
                true, "", true, true, "", "", "");
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

    private static LoginRequest request() {
        return new LoginRequest("admin", "wrong-password", "captcha-key", "ABCD");
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
