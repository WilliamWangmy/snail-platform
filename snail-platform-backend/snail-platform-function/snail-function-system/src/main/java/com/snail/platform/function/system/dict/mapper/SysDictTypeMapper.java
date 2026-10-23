package com.snail.platform.function.system.dict.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.dict.entity.SysDictType;

/**
 * 职责:sys_dict_type 数据访问。租户托管表,dictType 租户内唯一由库内唯一键兜底。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:dict 子域 service。
 */
public interface SysDictTypeMapper extends BaseMapper<SysDictType> {
}
