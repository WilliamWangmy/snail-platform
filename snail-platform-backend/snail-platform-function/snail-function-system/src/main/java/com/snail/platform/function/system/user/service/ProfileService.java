package com.snail.platform.function.system.user.service;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.redis.SnailRateGuard;
import com.snail.platform.framework.redis.SnailRedisKeys;
import com.snail.platform.function.system.org.entity.SysOrg;
import com.snail.platform.function.system.org.mapper.SysOrgMapper;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.support.CacheEvictor;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.dto.ChangePasswordRequest;
import com.snail.platform.function.system.user.dto.ForceChangePasswordRequest;
import com.snail.platform.function.system.user.dto.ProfileVO;
import com.snail.platform.function.system.user.dto.UpdateProfileRequest;
import com.snail.platform.function.system.user.dto.UserIdentityVO;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.entity.SysUserIdentity;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import com.snail.platform.function.system.user.mapper.SysUserIdentityMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:个人中心——本人档案读取、自助改资料、自助改密、强制下线自己。
 * 改密防爆破:原密码错按用户计数锁定(5 次/10 分钟,SnailRateGuard 原子计数);
 * 改密成功 = 口令更新 + token_version 自增同语句 + userSession 失效——全部存量令牌下一次请求即 401。
 * 自助是本人操作,不走数据权限闸(自己的行恒可见);只改白名单字段,契约外字段天然改不到。
 * 上游:ProfileController;下游:sys_user/sys_org/sys_tenant 读、SnailRateGuard、UserService.forceLogout。
 */
@Service
public class ProfileService {

    /** 原密码失败锁定阈值 */
    private static final int MAX_PWD_FAILS = 5;

    /** 失败计数窗口与锁定时长 */
    private static final Duration PWD_FAIL_WINDOW = Duration.ofMinutes(10);

    /** 用户表读写 */
    private final SysUserMapper userMapper;

    /** 组织表读,档案展示 */
    private final SysOrgMapper orgMapper;

    /** 租户表读,档案展示(ignore 表) */
    private final SysTenantMapper tenantMapper;

    /** 原密码失败计数 */
    private final SnailRateGuard rateGuard;

    /** 强制下线复用用户域单点 */
    private final UserService userService;

    /** 本人快照缓存的点清出口 */
    private final CacheEvictor cacheEvictor;

    /** 授权关系读:档案里的角色名快照 */
    private final SysUserRoleMapper userRoleMapper;

    /** 角色表读:roleId 回查角色名 */
    private final SysRoleMapper roleMapper;

    /** 第三方身份绑定读写:本人列表与自助解绑(豁免表,tenant_id 不参与过滤) */
    private final SysUserIdentityMapper userIdentityMapper;

    public ProfileService(SysUserMapper userMapper, SysOrgMapper orgMapper, SysTenantMapper tenantMapper,
                          SnailRateGuard rateGuard, UserService userService, CacheEvictor cacheEvictor,
                          SysUserRoleMapper userRoleMapper, SysRoleMapper roleMapper,
                          SysUserIdentityMapper userIdentityMapper) {
        this.userMapper = userMapper;
        this.orgMapper = orgMapper;
        this.tenantMapper = tenantMapper;
        this.rateGuard = rateGuard;
        this.userService = userService;
        this.cacheEvictor = cacheEvictor;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.userIdentityMapper = userIdentityMapper;
    }

    /** 本人档案:租户拦截器圈定 + user_id 自证,读不到即异常 */
    public ProfileVO me(Long userId) {
        SysUser user = requireSelf(userId);
        SysOrg org = user.getOrgId() == null ? null : orgMapper.selectById(user.getOrgId());
        SysTenant tenant = tenantMapper.selectById(user.getTenantId());
        return new ProfileVO(user.getUserId(), user.getUsername(), user.getNickname(), user.getEmail(),
                user.getPhone(), org == null ? null : org.getName(), tenant == null ? null : tenant.getName(),
                roleNamesOf(userId), user.getCreateTime());
    }

    /** 角色名快照:授权关系在本租户内,roleId 回查角色名;未授权即空列表 */
    private List<String> roleNamesOf(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream().map(SysRole::getRoleName).toList();
    }

    /** 本人第三方绑定列表:userId 恒来自登录上下文,无越权面;按绑定先后升序 */
    public List<UserIdentityVO> identities(Long userId) {
        return userIdentityMapper.selectList(Wrappers.<SysUserIdentity>lambdaQuery()
                        .eq(SysUserIdentity::getUserId, userId)
                        .orderByAsc(SysUserIdentity::getCreateTime))
                .stream().map(UserIdentityVO::of).toList();
    }

    /** 自助解绑:绑定行必须属于本人(防拿别人的绑定 ID 探测),错绑物理删;解绑后仍可用账号密码登录 */
    @Transactional
    public void unbindIdentity(Long userId, Long id) {
        SysUserIdentity identity = userIdentityMapper.selectById(id);
        if (identity == null || !userId.equals(identity.getUserId())) {
            throw BizException.badRequest("绑定不存在或已解绑");
        }
        userIdentityMapper.deleteById(id);
    }

    /** 自助改资料:只动 nickname/email/phone;昵称变化让登录人快照失效;手机号与建号/改档同规则查重 */
    @Transactional
    public void update(Long userId, UpdateProfileRequest request) {
        requireSelf(userId);
        userService.requirePhoneUniqueInTenant(request.phone(), userId);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getNickname, request.nickname().trim())
                .set(SysUser::getEmail, request.email())
                .set(SysUser::getPhone, request.phone())
                .eq(SysUser::getUserId, userId));
        cacheEvictor.evict("userSession", CacheEvictor.tenantKey(TenantContextHolder.get(), userId));
    }

    /** 自助改密:原密码计数防爆破,成功即令牌版本自增,全部设备重新登录 */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        SysUser user = requireSelf(userId);
        String guardKey = SnailRedisKeys.PROFILE_PWD_FAIL + userId;
        if (rateGuard.isLocked(guardKey, MAX_PWD_FAILS)) {
            throw BizException.badRequest("失败次数过多,请 10 分钟后再试");
        }
        if (!BCrypt.checkpw(request.oldPassword(), user.getPassword())) {
            rateGuard.record(guardKey, PWD_FAIL_WINDOW);
            throw BizException.badRequest("原密码不正确");
        }
        PasswordPolicy.validate(request.newPassword());
        if (BCrypt.checkpw(request.newPassword(), user.getPassword())) {
            throw BizException.badRequest("新密码不能与当前密码相同");
        }
        rateGuard.clear(guardKey);
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, BCrypt.hashpw(request.newPassword()))
                .set(SysUser::getPasswordModifyTime, LocalDateTime.now())
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        cacheEvictor.evict("userSession", CacheEvictor.tenantKey(TenantContextHolder.get(), userId));
    }

    /** 强制改密:仅待改密标记用户可用,成功即清标记并吊销全部存量令牌,客户端须重新登录 */
    @Transactional
    public void forceChangePassword(Long userId, ForceChangePasswordRequest request) {
        SysUser user = requireSelf(userId);
        if (user.getPasswordModifyTime() != null) {
            throw BizException.forbidden("无权限访问");
        }
        PasswordPolicy.validate(request.newPassword());
        if (BCrypt.checkpw(request.newPassword(), user.getPassword())) {
            throw BizException.badRequest("新密码不能与当前密码相同");
        }
        userMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                .set(SysUser::getPassword, BCrypt.hashpw(request.newPassword()))
                .set(SysUser::getPasswordModifyTime, LocalDateTime.now())
                .setSql("token_version = token_version + 1")
                .eq(SysUser::getUserId, userId));
        cacheEvictor.evict("userSession", CacheEvictor.tenantKey(TenantContextHolder.get(), userId));
    }

    /** 强制下线自己的全部会话(丢手机/离开公用机场景),复用用户域单点 */
    public void forceLogout(Long userId) {
        userService.forceLogout(userId);
    }

    /** 取本人行:userId 恒由控制器从登录上下文传入,查不到就是数据异常,不给越权留可走通的分支 */
    private SysUser requireSelf(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.badRequest("用户不存在");
        }
        return user;
    }
}
