package com.snail.platform.function.workflow.task.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 职责:转办入参——把任务改派给谁,附一句缘由。
 * 上游:待办页的转办弹窗(人选列表由前端用户选择器给);下游:WorkflowTaskService 校验后改派引擎任务并落台账。
 * 口径:目标人必须是当前租户内的已有用户,service 拿用户档案验一次——把任务派给不存在的人或别的租户,
 * 等于凭空造出一条谁也办不了的待办。
 */
public record WorkflowTransferRequest(
        /** 转办目标用户 ID */
        @NotNull(message = "请选择转办目标")
        Long targetUserId,
        /** 转办缘由 */
        @Size(max = 500, message = "转办说明不能超过 500 字")
        String comment) {
}
