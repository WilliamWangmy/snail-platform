package com.snail.platform.function.system.dict.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增字典数据契约。dictValue 是业务落库值,同类型内唯一且建后不可改,故只出现在新增。
 * 上游:字典数据管理页;下游:DictService.createData。
 */
@Schema(description = "新增字典数据请求")
public record DictDataCreateRequest(

        @Schema(description = "所属字典类型ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请指定字典类型")
        Long dictId,

        @Schema(description = "字典标签", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典标签")
        @Size(max = 100, message = "字典标签过长")
        String dictLabel,

        @Schema(description = "字典值,同类型内唯一", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典值")
        @Size(max = 100, message = "字典值过长")
        String dictValue,

        @Schema(description = "同类型内显示顺序,默认 0")
        @Min(value = 0, message = "顺序不能为负")
        Integer sort,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
