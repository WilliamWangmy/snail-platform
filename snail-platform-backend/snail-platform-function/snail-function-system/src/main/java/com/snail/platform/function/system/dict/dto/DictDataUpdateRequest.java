package com.snail.platform.function.system.dict.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改字典数据契约。刻意不含 dictValue 与 dictId——值建后不可改、归属类型不可迁移。
 * 上游:字典数据管理页;下游:DictService.updateData。
 */
@Schema(description = "修改字典数据请求")
public record DictDataUpdateRequest(

        @Schema(description = "字典标签", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典标签")
        @Size(max = 100, message = "字典标签过长")
        String dictLabel,

        @Schema(description = "同类型内显示顺序")
        @Min(value = 0, message = "顺序不能为负")
        Integer sort,

        @Schema(description = "状态:0 正常 1 停用")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1")
        Integer status,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
