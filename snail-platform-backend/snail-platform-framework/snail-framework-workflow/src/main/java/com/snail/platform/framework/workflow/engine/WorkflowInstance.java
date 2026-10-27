package com.snail.platform.framework.workflow.engine;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:流程实例的只读视图,同时覆盖在途与历史(ended 区分)。
 * 上游:WorkflowEngineGateway 的发起与实例查询;下游:function-workflow 的实例列表、详情与"一单一条"校验。
 * 口径:startUserId 是引擎历史里的身份字符串(平台 userId 的字符串形态),显示名由业务侧用留痕快照或 UserBriefPort 取,
 * 网关不回查用户档案(方案 §3.2)。
 * currentActivityIds 来自引擎的在途活动 ID 集合(并行网关下会有多个);已结束实例为空集合。
 */
public record WorkflowInstance(
        /** 流程实例 ID */
        String id,
        /** 流程定义 ID */
        String definitionId,
        /** 流程标识 */
        String definitionKey,
        /** 业务单号,与 businessType 一起构成唯一关联键 */
        String businessKey,
        /** 实例名(默认取定义名) */
        String name,
        /** 发起人 ID 字符串;异步/系统触发为 null */
        String startUserId,
        /** 当前停留的活动 ID;已结束为空集合 */
        List<String> currentActivityIds,
        /** 开始时刻 */
        LocalDateTime startTime,
        /** 结束时刻;在途为 null */
        LocalDateTime endTime,
        /** 是否已结束(含驳回与撤销,业务结果看留痕与状态回写) */
        boolean ended) {

    public WorkflowInstance {
        currentActivityIds = currentActivityIds == null ? List.of() : List.copyOf(currentActivityIds);
    }
}
