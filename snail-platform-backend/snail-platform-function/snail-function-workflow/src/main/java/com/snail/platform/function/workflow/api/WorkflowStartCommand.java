package com.snail.platform.function.workflow.api;

import java.util.Map;

/**
 * 职责:业务侧发起流程的入参契约(方案 §7)。
 * 上游:业务 service 组装;下游:WorkflowApi.start 与审批台账。
 * 口径:发起人不由这里传——网关注入当前登录人,业务侧自选发起人等于允许把待办投给任意人(方案 §3.2)。
 * businessType 决定流程结果回调给哪个业务监听器,必须与业务模块自己的 WorkflowOutcomeListener.businessType 同值。
 */
public record WorkflowStartCommand(
        /** 流程标识,等于模型 key,也是 BPMN 里的 process id */
        String definitionKey,
        /** 业务类型:与 businessKey 一起构成唯一关联键,并用于回调寻址 */
        String businessType,
        /** 业务单号:同单号在途只准一条,重复发起被拒 */
        String businessKey,
        /** 单据标题:落进流程实例名,待办列表与提醒文案都读它 */
        String title,
        /** 流程变量,只准标量(String/Integer/Double/Boolean);网关会拒绝对象型并剔除 initiator */
        Map<String, Object> variables) {
}
