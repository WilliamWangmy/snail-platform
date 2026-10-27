package com.snail.platform.function.workflow.task.dto;

import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import java.time.LocalDateTime;

/**
 * 职责:已办列表的一行——我办过的任务与它当时的业务坐标。
 * 上游:WorkflowTaskService 装配;下游:/workflow/task/done 出参与前端已办页。
 * 口径:handledAt 是引擎历史里的办结时刻,列表按它倒序;"办的是什么意见"在审批台账里,不重复存这行。
 * 实例可能已结束(instanceName 仍在),也可能仍在他处流转,所以业务坐标一律按实例视图给。
 */
public record WorkflowDoneVO(
        /** 引擎任务 ID */
        String taskId,
        /** 节点标识 */
        String taskDefinitionKey,
        /** 节点展示名 */
        String taskName,
        /** 流程实例 ID */
        String instanceId,
        /** 流程标识 */
        String definitionKey,
        /** 业务单号 */
        String businessKey,
        /** 实例标题 */
        String instanceName,
        /** 发起人用户 ID */
        Long initiatorId,
        /** 发起人展示名 */
        String initiatorName,
        /** 任务创建时刻(我接到它的时刻) */
        LocalDateTime createTime,
        /** 办结时刻 */
        LocalDateTime handledAt) {

    /** 由历史任务与实例视图装配 */
    public static WorkflowDoneVO of(WorkflowTask task, WorkflowInstance instance, String initiatorName) {
        return new WorkflowDoneVO(task.id(), task.taskDefinitionKey(), task.name(), task.instanceId(),
                instance == null ? null : instance.definitionKey(),
                instance == null ? null : instance.businessKey(),
                instance == null ? null : instance.name(),
                WorkflowTodoVO.toUserId(instance == null ? null : instance.startUserId()), initiatorName,
                task.createTime(), task.endTime());
    }
}
