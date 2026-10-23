package com.snail.platform.function.system.dict.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.dict.entity.SysDictData;

/**
 * 职责:sys_dict_data 数据访问。租户托管表,(dict_id, dict_value) 唯一由库内唯一键兜底。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:dict 子域 service。
 */
public interface SysDictDataMapper extends BaseMapper<SysDictData> {
}
