package com.snail.platform.function.workflow.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 职责:办理动作的入参——一句审批意见。
 * 上游:待办页的通过/驳回/签收弹窗;下游:WorkflowTaskService 落台账并推进引擎。
 * 口径:意见必填。"点了通过却没有任何痕迹"的审批在合规上等于没审,所以这里不允许空白意见;
 * 通过还是驳回由端点本身区分(URL 即语义),不在参数里再传一个布尔。
 */
public record WorkflowHandleRequest(
        /** 审批意见 */
        @NotBlank(message = "请填写审批意见")
        @Size(max = 500, message = "审批意见不能超过 500 字")
        String comment) {
}
