package com.snail.platform.function.workflow.instance.dto;

import com.snail.platform.framework.workflow.engine.WorkflowInstance;
import com.snail.platform.function.workflow.api.WorkflowStatus;
import java.time.LocalDateTime;

/**
 * 职责:实例列表/详情的一行——引擎坐标 + 平台判定的流程状态。
 * 上游:WorkflowInstanceService 装配;下游:/workflow/instance/started 与 /{instanceId} 出参。
 * 口径:status 由 service 按"在途即 RUNNING,已结束按台账最后一个定结果动作反推"给出,不是引擎字段——
 * 引擎只知道实例结束了,不知道为什么结束。
 * initiatorId 在"我发起的"里恒等于当前登录人,详情里带上是给撤销按钮做归属判断用的。
 */
public record WorkflowInstanceVO(
        /** 流程实例 ID */
        String instanceId,
        /** 流程标识 */
        String definitionKey,
        /** 实例标题(发起时的单据标题,缺省为流程定义名) */
        String title,
        /** 业务单号;裸流程为 null */
        String businessKey,
        /** 流程状态 */
        WorkflowStatus status,
        /** 发起人用户 ID */
        Long initiatorId,
        /** 发起时刻 */
        LocalDateTime startTime,
        /** 结束时刻;在途为 null */
        LocalDateTime endTime) {

    /** 由实例视图与判定出的状态装配;发起人 ID 解析不了留空而不是整行失败 */
    public static WorkflowInstanceVO of(WorkflowInstance instance, WorkflowStatus status) {
        return new WorkflowInstanceVO(instance.id(), instance.definitionKey(), instance.name(),
                instance.businessKey(), status, parseUserId(instance.startUserId()),
                instance.startTime(), instance.endTime());
    }

    /** 引擎身份字符串 → 平台用户 ID;非数字(系统触发、脏数据)按缺失处理 */
    private static Long parseUserId(String raw) {
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
