package com.snail.platform.function.workflow.instance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:撤销在途流程的入参。
 * 上游:POST /workflow/instance/{instanceId}/withdraw;下游:撤销原因进审批台账的 comment 列。
 * 口径:原因必填——撤销会让审批人的待办凭空消失,不留缘由就是让人白跑一趟。
 */
public record WorkflowWithdrawRequest(
        /** 撤销原因 */
        @NotBlank(message = "请填写撤销原因")
        @Size(max = 500, message = "撤销原因不能超过 500 字")
        String reason) {
}
