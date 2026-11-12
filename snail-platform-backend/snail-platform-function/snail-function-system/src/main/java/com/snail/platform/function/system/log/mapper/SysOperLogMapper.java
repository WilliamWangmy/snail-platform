package com.snail.platform.function.system.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.log.entity.SysOperLog;

/**
 * 职责:sys_oper_log 数据访问。业务写入只有切面落库(增)与 log 子域按保留期清历史(删),无更新路径;查询侧显式写 tenant_id 条件(本表在拦截器豁免清单)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:log 子域查询与导出。
 */
public interface SysOperLogMapper extends BaseMapper<SysOperLog> {
}
