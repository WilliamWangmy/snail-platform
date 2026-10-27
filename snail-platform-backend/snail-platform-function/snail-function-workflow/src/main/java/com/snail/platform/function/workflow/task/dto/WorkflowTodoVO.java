package com.snail.platform.function.workflow.task.dto;

import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.framework.workflow.engine.WorkflowTask;
import java.time.LocalDateTime;

/**
 * 职责:待办列表的一行——引擎任务 + 实例业务坐标 + 人的展示名。
 * 上游:WorkflowTaskService 装配;下游:/workflow/task/todo 出参与前端待办页。
 * 口径:instanceName/businessKey 由批量实例查询带入,instance 缺失(极小概率:任务刚被办结)时留空而不是整行消失;
 * assignee 为空表示未签收,此时 claimed=false,由候选角色内的任何人签收或直接办理。
 * 展示名是查询时刻从用户档案取的现值,不是快照——列表要说"现在叫什么",台账才说"当时叫什么"。
 */
public record WorkflowTodoVO(
        /** 引擎任务 ID,办理动作按它寻址 */
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
        /** 当前办理人用户 ID;未签收为 null */
        Long assigneeId,
        /** 当前办理人展示名;未签收为 null */
        String assigneeName,
        /** 是否已签收 */
        boolean claimed,
        /** 任务创建时刻 */
        LocalDateTime createTime,
        /** 到期时刻 */
        LocalDateTime dueDate) {

    /** 由任务与实例视图装配;两个 name 由 service 批量取档后传入 */
    public static WorkflowTodoVO of(WorkflowTask task, WorkflowInstance instance,
                                    String initiatorName, String assigneeName) {
        return new WorkflowTodoVO(task.id(), task.taskDefinitionKey(), task.name(), task.instanceId(),
                instance == null ? null : instance.definitionKey(),
                instance == null ? null : instance.businessKey(),
                instance == null ? null : instance.name(),
                toUserId(instance == null ? null : instance.startUserId()), initiatorName,
                toUserId(task.assignee()), assigneeName, task.assignee() != null,
                task.createTime(), task.dueDate());
    }

    /** 引擎侧身份是字符串,平台侧寻址是 Long;解析不了就当没有(不让一行数据因为一个脏 id 整体失败) */
    public static Long toUserId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
