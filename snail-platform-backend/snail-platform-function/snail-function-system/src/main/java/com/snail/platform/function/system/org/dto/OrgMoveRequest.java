package com.snail.platform.function.system.org.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 职责:组织节点移动契约。只提交新父,服务端负责 ancestors 子树前缀替换与环校验。
 * 上游:组织树拖拽;下游:OrgService.move。
 */
@Schema(description = "移动组织请求")
public record OrgMoveRequest(

        @Schema(description = "新父组织ID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "缺少新父组织")
        Long newParentId) {
}
