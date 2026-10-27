package com.snail.platform.function.workflow.api;

/**
 * 职责:平台流程状态口径——业务侧唯一需要理解的流程语义(方案 §7)。
 * 上游:WorkflowApi.status 的返回与 WorkflowOutcome 的载荷;下游:业务表 flow_status 的回写。
 * 口径:这里描述"流程此刻怎么样",业务表那一列描述"我上次被告知的结果",两者靠回调对齐,不靠 join 引擎表。
 * 驳回后重新提交会开出新实例,所以状态永远按业务单号的最新一条实例判定。
 */
public enum WorkflowStatus {

    /** 这个业务单号从没进过流程 */
    NONE,

    /** 在途:还有节点没办完 */
    RUNNING,

    /** 已结束且最后一个审批动作是通过 */
    APPROVED,

    /** 已结束且最后一个审批动作是驳回 */
    REJECTED,

    /** 发起人撤销,在途待办一并消失 */
    WITHDRAWN
}
