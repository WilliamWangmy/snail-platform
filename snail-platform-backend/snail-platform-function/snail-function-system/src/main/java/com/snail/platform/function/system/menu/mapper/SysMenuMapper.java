package com.snail.platform.function.system.menu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.menu.entity.SysMenu;

/**
 * 职责:sys_menu 数据访问。全局目录表,无 tenant_id,已在租户拦截器 ignore 清单内。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:menu 子域菜单维护与 role 子域权限解析。
 */
public interface SysMenuMapper extends BaseMapper<SysMenu> {
}
