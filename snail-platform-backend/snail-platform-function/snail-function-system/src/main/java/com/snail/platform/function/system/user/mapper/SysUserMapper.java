package com.snail.platform.function.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.user.entity.SysUser;

/**
 * 职责:sys_user 数据访问。租户托管表,一切语句由拦截器自动加 tenant_id。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:user 子域 service。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
