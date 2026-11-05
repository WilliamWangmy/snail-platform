package com.snail.platform.function.system.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.user.entity.SysUserIdentity;

/**
 * 职责:sys_user_identity 数据访问。查询侧显式写条件(本表在拦截器豁免清单,tenant_id 不参与过滤)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:auth 登录链绑定定位与用户详情只读展示。
 */
public interface SysUserIdentityMapper extends BaseMapper<SysUserIdentity> {
}
