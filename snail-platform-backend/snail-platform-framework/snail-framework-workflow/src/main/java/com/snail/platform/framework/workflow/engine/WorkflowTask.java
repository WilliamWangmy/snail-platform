package com.snail.platform.framework.workflow.engine;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:引擎待办/在办任务的只读视图,屏蔽 Flowable 的 Task。
 * 上游:WorkflowEngineGateway 的任务查询;下游:function-workflow 的待办列表与办理入口前置校验。
 * 口径:candidateGroups 是 roleId 的字符串形态(方案 §3.1 候选组用 roleId,天然全局唯一)。
 * 刻意不带业务单号:Flowable 的 Task 上没有 businessKey(它在实例上),逐行回查就是 N+1;
 * 列表页需要业务单号时,由调用方收集 instanceId 走 WorkflowEngineGateway.findInstancesByIds 一次批量取回。
 */
public record WorkflowTask(
        /** 任务 ID */
        String id,
        /** 任务节点标识(BPMN userTask id),业务据此判断"哪一步" */
        String taskDefinitionKey,
        /** 任务展示名 */
        String name,
        /** 流程定义 ID */
        String definitionId,
        /** 流程实例 ID,业务单号据此批量取回 */
        String instanceId,
        /** 办理人 ID 字符串;未签收为 null */
        String assignee,
        /** 候选角色 ID 字符串集合;恒非 null */
        List<String> candidateGroups,
        /** 任务创建时刻 */
        LocalDateTime createTime,
        /** 到期时刻;未设为 null */
        LocalDateTime dueDate,
        /** 办理完成时刻;在途任务为 null,已办列表按它排时间轴 */
        LocalDateTime endTime) {

    public WorkflowTask {
        candidateGroups = candidateGroups == null ? List.of() : List.copyOf(candidateGroups);
    }
}
