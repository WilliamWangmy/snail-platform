package com.snail.platform.framework.workflow.engine;

/**
 * 职责:一次部署的结果——引擎侧部署单与该部署内唯一流程定义的指针。
 * 上游:WorkflowEngineGateway.deploy;下游:function-workflow 的模型发布(回写 deployment_id/process_definition_id/版本号)。
 * 口径:网关已校验"一次部署只含一个流程定义",所以这里单个 definition 字段即可,不返回列表。
 */
public record WorkflowDeployment(
        /** 引擎部署单 ID */
        String deploymentId,
        /** 流程定义 ID(启动实例用的就是它) */
        String processDefinitionId,
        /** 流程标识,等于 BPMN 里的 process id */
        String processDefinitionKey,
        /** 引擎版本号,同 key 每次部署自增 */
        int version) {
}
