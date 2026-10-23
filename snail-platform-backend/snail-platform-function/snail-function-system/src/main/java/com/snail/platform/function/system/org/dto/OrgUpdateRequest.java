package com.snail.platform.function.system.org.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改组织契约——改名/排序/备注/停用启用。父级不在其中,移动走独立的 move 端点,
 * 因为移动要整棵子树重写 ancestors,与轻量改属性是两个代价量级。
 * 上游:组织树页面;下游:OrgService.update。
 */
@Schema(description = "修改组织请求")
public record OrgUpdateRequest(

        @Schema(description = "组织名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入组织名称")
        @Size(max = 50, message = "组织名称过长")
        String name,

        @Schema(description = "同级显示顺序")
        Integer sort,

        @Schema(description = "状态:0 正常 1 停用;停用只影响该节点被新选为归属")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1")
        Integer status,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
