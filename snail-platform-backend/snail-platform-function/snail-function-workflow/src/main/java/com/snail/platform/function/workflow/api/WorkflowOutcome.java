package com.snail.platform.function.workflow.api;

/**
 * 职责:流程出结果时递给业务侧的载荷(方案 §7 的回调契约)。
 * 上游:WorkflowOutcomeDispatcher;下游:业务模块的 WorkflowOutcomeListener。
 * 口径:businessType + businessKey 定位单据,instanceId 用于幂等。回调只保证"提交后至少送达一次",
 * 不保证只送达一次,业务侧按 (instanceId, status) 判重,而不是假设自己只会被叫到一回。
 */
public record WorkflowOutcome(
        /** 业务类型,与发起时同值 */
        String businessType,
        /** 业务单号 */
        String businessKey,
        /** 流程实例 ID */
        String instanceId,
        /** 流程最终状态,只会是 APPROVED / REJECTED / WITHDRAWN */
        WorkflowStatus status) {
}
