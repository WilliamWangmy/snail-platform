package com.snail.platform.function.system.dict.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改字典类型契约。刻意不含 dictType——建后不可改,接口收不到就不存在被改的路径。
 * 上游:字典管理页面;下游:DictService.updateType。
 */
@Schema(description = "修改字典类型请求")
public record DictTypeUpdateRequest(

        @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典名称")
        @Size(max = 100, message = "字典名称过长")
        String dictName,

        @Schema(description = "状态:0 正常 1 停用")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1")
        Integer status,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
