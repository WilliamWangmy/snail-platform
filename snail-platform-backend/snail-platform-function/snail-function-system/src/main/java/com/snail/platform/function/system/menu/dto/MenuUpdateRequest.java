package com.snail.platform.function.system.menu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:修改菜单契约。menu_type/target/scope/perm_code 建后不可改,故不在契约里;
 * status 可改,但内置节点禁止停用由 MenuService 守。
 * 上游:平台菜单管理页面;下游:MenuService.update。
 */
@Schema(description = "修改菜单请求")
public record MenuUpdateRequest(

        @Schema(description = "菜单名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入菜单名称")
        @Size(max = 50, message = "菜单名称过长")
        String menuName,

        @Schema(description = "路由/地址:站内与数据大屏为前端路由,外链为完整 URL")
        @Size(max = 500, message = "路由/地址过长")
        String path,

        @Schema(description = "前端组件路径:站内与数据大屏菜单必填,目录与外链留空")
        @Size(max = 255, message = "组件路径过长")
        String component,

        @Schema(description = "图标名")
        @Size(max = 100, message = "图标名过长")
        String icon,

        @Schema(description = "同级显示顺序,不传不改")
        @Min(value = 0, message = "顺序不能为负")
        Integer sort,

        @Schema(description = "显示:0 显示 1 隐藏,不传不改;按钮固定 0")
        @Min(value = 0, message = "显示取值 0/1")
        @Max(value = 1, message = "显示取值 0/1")
        Integer visible,

        @Schema(description = "状态:0 正常 1 停用,不传不改;内置节点不可停用")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1")
        Integer status,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
