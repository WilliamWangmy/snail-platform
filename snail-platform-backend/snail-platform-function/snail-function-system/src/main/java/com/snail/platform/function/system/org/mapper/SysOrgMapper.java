package com.snail.platform.function.system.org.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.org.entity.SysOrg;

/**
 * 职责:sys_org 数据访问。树操作走 ancestors 字符串口径;子树清单查询在数据权限阶段追加。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:org 子域 service。
 */
public interface SysOrgMapper extends BaseMapper<SysOrg> {
}
