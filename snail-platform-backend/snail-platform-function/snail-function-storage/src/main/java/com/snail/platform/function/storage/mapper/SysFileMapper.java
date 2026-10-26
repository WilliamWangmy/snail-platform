package com.snail.platform.function.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.storage.entity.SysFile;

/**
 * 职责:sys_file 数据访问,基础 CRUD 由 BaseMapper 提供;租户隔离与逻辑删除由拦截器自动注入。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:storage 模块 service。
 */
public interface SysFileMapper extends BaseMapper<SysFile> {
}
