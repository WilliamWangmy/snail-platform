package com.snail.platform.function.system.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.menu.entity.SysRoleMenu;

/**
 * 职责:sys_role_menu 授权行读写。联合主键表:删除/覆盖按 (roleId, menuId) 双字段条件走,不依赖 MP 单主键语义。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:role 子域授权写入与权限解析。
 */
public interface SysRoleMenuMapper extends BaseMapper<SysRoleMenu> {
}
