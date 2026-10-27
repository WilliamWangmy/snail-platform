package com.snail.platform.function.workflow.api;

/**
 * 职责:业务侧接收流程结果的端口(方案 §7)——每个业务类型实现一个,平台按 Spring 容器归集。
 * 上游:WorkflowOutcomeDispatcher 在流程办理收尾或撤销之后回调;
 * 下游:业务 service 更新自己那张单据表的 flow_status 与 flow_instance_id。
 * 口径:回调在流程动作所在事务提交之后触发,审批已经成立,这里抛异常不会把单子退回未办——
 * 异常落日志,单据停留在旧状态由业务侧自己补偿。
 * 租户与登录人上下文在回调时仍然有效,但已经不在原事务里:写库请走业务 service 自己的事务边界。
 */
public interface WorkflowOutcomeListener {

    /** 认领的业务类型,须与 WorkflowStartCommand.businessType 同值;同一业务类型注册两个实现会直接拒绝启动 */
    String businessType();

    /** 收到流程结果;实现必须按 (instanceId, status) 幂等 */
    void onOutcome(WorkflowOutcome outcome);
}
