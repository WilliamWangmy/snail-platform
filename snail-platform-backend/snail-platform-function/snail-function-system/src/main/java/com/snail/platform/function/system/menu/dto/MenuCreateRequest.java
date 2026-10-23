package com.snail.platform.function.system.menu.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 职责:新增菜单契约。menu_type/target/scope/perm_code 建后不可改,故只出现在新增;
 * 各类型对 path/component/perm_code 的必填口径由 MenuService 按三元组校验。
 * 上游:平台菜单管理页面;下游:MenuService.create。is_system 不经接口,只有种子能置 1。
 */
@Schema(description = "新增菜单请求")
public record MenuCreateRequest(

        @Schema(description = "父菜单ID,根节点传 0 或不传", defaultValue = "0")
        @Min(value = 0, message = "父菜单ID不能为负")
        Long parentId,

        @Schema(description = "菜单名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入菜单名称")
        @Size(max = 50, message = "菜单名称过长")
        String menuName,

        @Schema(description = "类型:1 目录 2 菜单 3 按钮", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择菜单类型")
        @Min(value = 1, message = "菜单类型取值 1-3")
        @Max(value = 3, message = "菜单类型取值 1-3")
        Integer menuType,

        @Schema(description = "展示端:1 站内 2 数据大屏 3 外链", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择展示端")
        @Min(value = 1, message = "展示端取值 1-3")
        @Max(value = 3, message = "展示端取值 1-3")
        Integer target,

        @Schema(description = "归属面:1 平台 2 租户", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择归属面")
        @Min(value = 1, message = "归属面取值 1-2")
        @Max(value = 2, message = "归属面取值 1-2")
        Integer scope,

        @Schema(description = "路由/地址:站内与数据大屏为前端路由,外链为完整 URL")
        @Size(max = 500, message = "路由/地址过长")
        String path,

        @Schema(description = "前端组件路径:站内与数据大屏菜单必填,目录与外链留空")
        @Size(max = 255, message = "组件路径过长")
        String component,

        @Schema(description = "权限码:菜单与按钮必填,目录与外链留空;建后不可改")
        @Size(max = 100, message = "权限码过长")
        String permCode,

        @Schema(description = "图标名")
        @Size(max = 100, message = "图标名过长")
        String icon,

        @Schema(description = "同级显示顺序,默认 0")
        @Min(value = 0, message = "顺序不能为负")
        Integer sort,

        @Schema(description = "显示:0 显示 1 隐藏,默认 0;按钮固定 0")
        @Min(value = 0, message = "显示取值 0/1")
        @Max(value = 1, message = "显示取值 0/1")
        Integer visible,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
