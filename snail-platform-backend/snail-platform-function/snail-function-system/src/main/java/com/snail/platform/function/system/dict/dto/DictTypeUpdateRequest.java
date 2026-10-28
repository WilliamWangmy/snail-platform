package com.snail.platform.function.system.dict.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改字典类型契约。刻意不含 dict_type(建后不可改)与 status(启停走独立端点、单独留痕)。
 * 上游:字典管理页面;下游:DictService.updateType。
 */
@Schema(description = "修改字典类型请求")
public record DictTypeUpdateRequest(

        @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典名称")
        @Size(max = 100, message = "字典名称过长")
        String dictName,

        @Schema(description = "备注,不传即清空")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
