package com.snail.platform.function.workflow.instance.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:轨迹页的出参——这单跑到哪了、每一步谁办的说了什么。
 * 上游:WorkflowInstanceService(步骤取审批台账,在途节点取引擎);下游:/workflow/instance/{id}/trace 与前端轨迹页。
 * 口径:steps 的人名与机构名是台账里的**当时快照**,不是档案现值——轨迹是证据,要还原发生那一刻的样子;
 * waiting 的人名是查询时刻的档案现值,因为"现在该谁办"必须说当前的情况。两套口径同屏是刻意的,不是混用。
 * 未签收的在途节点 assigneeId 为 null,前端按"待候选角色办理"展示:候选角色名要跨模块取 system 的角色档案,v1 不做。
 * diagram 只在调用方显式要求时装配:轨迹接口同时服务于列表页的轻量摘要,流程图原文与历史活动对摘要页是多余负担。
 */
public record WorkflowTraceVO(
        /** 实例概要(含平台判定的流程状态) */
        WorkflowInstanceVO instance,
        /** 已发生的动作,按时刻正序 */
        List<Step> steps,
        /** 当前停在哪些节点(并行网关下会有多个) */
        List<Waiting> waiting,
        /** 流程图(定义原文 + 节点状态);未请求或定义原文不可用时为 null */
        Diagram diagram) {

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
            /** 节点标识(BPMN userTask id),流程图据此点亮当前节点 */
            String activityId,
            /** 节点名 */
            String taskName,
            /** 办理人 ID;未签收为 null */
            Long assigneeId,
            /** 办理人显示名(查询时刻);未签收为 null */
            String assigneeName) {
    }

    /**
     * 流程图:定义原文交给前端 bpmn-js 渲染,节点状态只给已发生的活动。
     * 状态取值 DONE(已通过)/ ACTIVE(当前停留)/ REJECTED(驳回路径)/ WITHDRAWN(撤销终止点);
     * 没有状态行的节点即未执行,由前端保持默认配色。连线状态由前端按两端节点状态推导。
     */
    public record Diagram(
            /** BPMN 定义原文;读取失败为 null,流程图降级为空态 */
            String xml,
            /** 已发生的流程节点及其状态 */
            List<Node> nodes) {
    }

    /** 流程图里的一个节点状态 */
    public record Node(
            /** BPMN 元素 id */
            String activityId,
            /** DONE / ACTIVE / REJECTED / WITHDRAWN */
            String state) {
    }
}
