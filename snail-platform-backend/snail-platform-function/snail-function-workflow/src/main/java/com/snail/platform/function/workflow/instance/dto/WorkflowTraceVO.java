package com.snail.platform.function.workflow.instance.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:轨迹页的出参——这单跑到哪了、每一步谁办的说了什么。
 * 上游:WorkflowInstanceService(步骤取审批台账,在途节点取引擎);下游:/workflow/instance/{id}/trace 与前端轨迹页。
 * 口径:steps 的人名与机构名是台账里的**当时快照**,不是档案现值——轨迹是证据,要还原发生那一刻的样子;
 * waiting 的人名是查询时刻的档案现值,因为"现在该谁办"必须说当前的情况。两套口径同屏是刻意的,不是混用。
 * 未签收的在途节点 assigneeId 为 null,前端按"待候选角色办理"展示:候选角色名要跨模块取 system 的角色档案,v1 不做。
 */
public record WorkflowTraceVO(
        /** 实例概要(含平台判定的流程状态) */
        WorkflowInstanceVO instance,
        /** 已发生的动作,按时刻正序 */
        List<Step> steps,
        /** 当前停在哪些节点(并行网关下会有多个) */
        List<Waiting> waiting) {

    /** 台账里的一步 */
    public record Step(
            /** 动作:start/claim/approve/reject/transfer/withdraw */
            String action,
            /** 引擎任务 ID;实例级动作为 null */
            String taskId,
            /** 节点名快照 */
            String taskName,
            /** 操作人 ID */
            Long operatorId,
            /** 操作人显示名快照 */
            String operatorName,
            /** 转办目标人显示名快照;非转办为 null */
            String targetUserName,
            /** 审批意见或撤销原因 */
            String comment,
            /** 发生时刻 */
            LocalDateTime time) {
    }

    /** 此刻还压在谁手里的节点 */
    public record Waiting(
            /** 引擎任务 ID */
            String taskId,
            /** 节点名 */
            String taskName,
            /** 办理人 ID;未签收为 null */
            Long assigneeId,
            /** 办理人显示名(查询时刻);未签收为 null */
            String assigneeName) {
    }
}
