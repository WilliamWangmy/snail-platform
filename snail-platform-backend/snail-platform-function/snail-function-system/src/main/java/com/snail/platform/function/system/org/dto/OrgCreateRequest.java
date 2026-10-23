package com.snail.platform.function.system.org.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增组织子节点契约。根节点由租户开通模板生成,本接口只长子树,parentId 必须已存在。
 * 上游:组织树页面的"新增子节点";下游:OrgService.create。
 */
@Schema(description = "新增组织请求")
public record OrgCreateRequest(

        @Schema(description = "父组织ID,须为租户树内已存在的节点", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "缺少父组织")
        @PositiveOrZero(message = "父组织ID不合法")
        Long parentId,

        @Schema(description = "组织名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入组织名称")
        @Size(max = 50, message = "组织名称过长")
        String name,

        @Schema(description = "同级显示顺序,默认 0")
        @PositiveOrZero(message = "顺序不能为负")
        Integer sort,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
