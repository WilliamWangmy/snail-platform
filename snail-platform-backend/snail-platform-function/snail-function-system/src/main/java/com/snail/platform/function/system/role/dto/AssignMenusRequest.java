package com.snail.platform.function.system.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * 职责:角色菜单授权契约——menuIds 为整包提交,空数组表示清空;平台面是否可提交由服务层二次判权。
 * 上游:角色管理页授权弹窗;下游:RoleMenuService.assign。
 */
@Schema(description = "角色菜单授权请求")
public record AssignMenusRequest(

        @Schema(description = "菜单ID全量集合", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择菜单")
        List<@NotNull(message = "菜单ID不能为空") @Positive(message = "菜单ID必须为正数") Long> menuIds) {
}
