package com.snail.platform.function.system.dict.dto;

import com.snail.platform.function.system.dict.entity.SysDictType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:字典类型出参对象;dictType 透出供管理页核对程序取值坐标。
 * 上游:DictService;下游:字典类型列表。
 */
@Schema(description = "字典类型信息")
public record DictTypeVO(

        @Schema(description = "字典类型ID")
        Long dictId,

        @Schema(description = "字典名称")
        String dictName,

        @Schema(description = "字典类型标识,建后不可改")
        String dictType,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间")
        LocalDateTime createTime) {

    public static DictTypeVO from(SysDictType type) {
        return new DictTypeVO(type.getDictId(), type.getDictName(), type.getDictType(),
                type.getStatus(), type.getRemark(), type.getCreateTime());
    }
}
