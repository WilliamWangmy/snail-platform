package com.snail.platform.function.system.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.tenant.entity.SysTenant;

/**
 * 职责:sys_tenant 数据访问,基础 CRUD 由 BaseMapper 提供。平台级表,不走租户过滤。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:tenant 子域 service。
 */
public interface SysTenantMapper extends BaseMapper<SysTenant> {
}
