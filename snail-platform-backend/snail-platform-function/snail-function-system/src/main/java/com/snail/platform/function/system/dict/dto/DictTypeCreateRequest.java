package com.snail.platform.function.system.dict.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增字典类型契约。dictType 是程序取值坐标,租户内唯一且建后不可改,故只出现在新增。
 * 上游:字典管理页面;下游:DictService.createType。
 */
@Schema(description = "新增字典类型请求")
public record DictTypeCreateRequest(

        @Schema(description = "字典名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典名称")
        @Size(max = 100, message = "字典名称过长")
        String dictName,

        @Schema(description = "字典类型标识,小写字母开头,租户内唯一", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入字典类型标识")
        @Size(min = 2, max = 100, message = "字典类型标识长度 2-100")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "字典类型标识只允许小写字母、数字、下划线且以字母开头")
        String dictType,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
