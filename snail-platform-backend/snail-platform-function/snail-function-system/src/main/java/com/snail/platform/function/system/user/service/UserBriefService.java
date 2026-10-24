package com.snail.platform.function.system.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.system.api.UserBriefPort;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import com.snail.platform.function.system.user.entity.SysUser;
import com.snail.platform.function.system.user.mapper.SysUserMapper;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 职责:UserBriefPort 的唯一实现——收件人快照与平台公告扇出的用户面数据源。
 * briefs 走当前租户口径;enabledUsersOfTenant 自行切到目标租户窗口,调用方无需关心上下文。
 * 上游:notify 模块经 api 包调用;下游:sys_user/sys_tenant 读(既有白名单边 user->tenant)。
 */
@Service
public class UserBriefService implements UserBriefPort {

    /** 用户启用状态:0 正常 */
    private static final int STATUS_ACTIVE = 0;

    /** 用户表读,简要信息事实源 */
    private final SysUserMapper userMapper;

    /** 租户表读,ignore 表,扇出前的存在性/状态校验 */
    private final SysTenantMapper tenantMapper;

    public UserBriefService(SysUserMapper userMapper, SysTenantMapper tenantMapper) {
        this.userMapper = userMapper;
        this.tenantMapper = tenantMapper;
    }

    @Override
    public List<UserBrief> briefs(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<Long> distinct = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(distinct).stream().map(UserBriefService::toBrief).toList();
    }

    @Override
    public List<UserBrief> enabledUsersOfTenant(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw BizException.badRequest("租户不存在");
        }
        if (Objects.equals(tenant.getStatus(), 1)) {
            throw BizException.badRequest("租户已停用");
        }
        return TenantContextHolder.asTenant(tenantId, () -> userMapper.selectList(
                        Wrappers.<SysUser>lambdaQuery().eq(SysUser::getStatus, STATUS_ACTIVE)))
                .stream().map(UserBriefService::toBrief).toList();
    }

    private static UserBrief toBrief(SysUser user) {
        return new UserBrief(user.getUserId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getStatus());
    }
}
