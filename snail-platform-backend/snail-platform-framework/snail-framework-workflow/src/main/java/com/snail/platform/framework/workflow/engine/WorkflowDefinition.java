package com.snail.platform.framework.workflow.engine;

/**
 * 职责:已发布流程定义的只读视图,屏蔽 Flowable 的 ProcessDefinition。
 * 上游:WorkflowEngineGateway 的定义查询;下游:function-workflow 的定义列表与版本指针校验。
 * 口径:只含当前租户可见的定义(网关强制 definitionTenantId);suspended 供"坏版本挂起"呈现。
 */
public record WorkflowDefinition(
        /** 流程定义 ID */
        String id,
        /** 流程标识 */
        String key,
        /** 展示名(BPMN process name) */
        String name,
        /** 版本号 */
        int version,
        /** 所属部署单 */
        String deploymentId,
        /** 是否已挂起 */
        boolean suspended) {
}
