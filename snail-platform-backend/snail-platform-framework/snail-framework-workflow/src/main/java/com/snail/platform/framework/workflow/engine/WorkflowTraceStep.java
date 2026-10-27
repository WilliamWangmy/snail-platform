package com.snail.platform.framework.workflow.engine;

import java.time.LocalDateTime;

/**
 * 职责:实例轨迹的一步(活动实例),供审批轨迹页画"走到哪、谁停过"。
 * 上游:WorkflowEngineGateway 的历史活动查询;下游:function-workflow 的轨迹视图与前端 Viewer 标注。
 * 口径:只给引擎侧事实(节点、类型、时刻、耗时、办理人字符串);
 * 审批意见与动作语义在 sys_workflow_task_record 留痕里,轨迹页由业务侧合并两者。
 */
public record WorkflowTraceStep(
        /** 节点标识(BPMN 元素 id) */
        String activityId,
        /** 节点展示名 */
        String name,
        /** 活动类型,如 userTask / exclusiveGateway / startEvent */
        String type,
        /** 该节点的办理人 ID 字符串;非人工节点为 null */
        String assignee,
        /** 进入时刻 */
        LocalDateTime startTime,
        /** 离开时刻;当前停留节点为 null */
        LocalDateTime endTime,
        /** 停留时长毫秒;未结束为 null */
        Long durationMillis) {
}
