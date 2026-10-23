package com.snail.platform.function.system.dict.dto;

import com.snail.platform.function.system.dict.entity.SysDictData;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:字典数据出参对象;管理页需要看到值、顺序与停用状态。
 * 上游:DictService;下游:字典数据列表。
 */
@Schema(description = "字典数据信息")
public record DictDataVO(

        @Schema(description = "字典数据ID")
        Long dictDataId,

        @Schema(description = "所属字典类型ID")
        Long dictId,

        @Schema(description = "字典标签")
        String dictLabel,

        @Schema(description = "字典值,建后不可改")
        String dictValue,

        @Schema(description = "同类型内显示顺序")
        Integer sort,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间")
        LocalDateTime createTime) {

    public static DictDataVO from(SysDictData data) {
        return new DictDataVO(data.getDictDataId(), data.getDictId(), data.getDictLabel(),
                data.getDictValue(), data.getSort(), data.getStatus(), data.getRemark(),
                data.getCreateTime());
    }
}
