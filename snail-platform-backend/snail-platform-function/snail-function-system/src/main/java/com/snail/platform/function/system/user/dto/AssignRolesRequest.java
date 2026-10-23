package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 职责:分配角色契约。全量覆盖语义——提交的集合即最终授权,服务端重刷该用户的 sys_user_role,无增量接口。
 * 上游:用户授权对话框;下游:UserService.assignRoles。
 */
@Schema(description = "分配角色请求")
public record AssignRolesRequest(

        @Schema(description = "角色ID全集,空数组即清空授权", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请提交角色集合(可空数组)")
        List<Long> roleIds) {
}
