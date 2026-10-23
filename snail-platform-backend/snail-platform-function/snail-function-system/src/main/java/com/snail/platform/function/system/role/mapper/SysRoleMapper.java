package com.snail.platform.function.system.role.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.role.entity.SysRole;

/**
 * 职责:sys_role 数据访问。租户托管表,roleCode 唯一性由库内唯一键兜底。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:role 子域 service。
 */
public interface SysRoleMapper extends BaseMapper<SysRole> {
}
