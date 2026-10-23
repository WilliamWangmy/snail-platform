package com.snail.platform.function.system.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.log.entity.SysLoginLog;

/**
 * 职责:sys_login_log 数据访问。只增;查询侧显式写 tenant_id 条件(本表在拦截器豁免清单)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:认证链留痕与 log 子域查询。
 */
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {
}
