package com.snail.platform.function.system.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.role.entity.SysUserRole;

/**
 * 职责:sys_user_role 授权行读写。联合主键表:删除/覆盖按 (userId, roleId) 双字段条件走,不依赖 MP 单主键语义。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:user/role 子域的角色分配。
 */
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
