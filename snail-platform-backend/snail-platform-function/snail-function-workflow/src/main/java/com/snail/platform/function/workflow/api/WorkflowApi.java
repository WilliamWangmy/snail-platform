package com.snail.platform.function.workflow.api;

/**
 * 职责:业务模块接入流程的唯一入口,住 workflow 的 api 包——跨模块只准 import 本包(ArchUnit 机械执法)。
 * 上游:各业务 service(它们不依赖 framework-workflow,更不碰 Flowable);
 * 下游:WorkflowInstanceService,再往下才是引擎网关。
 * 口径:三个方法都在调用方事务里执行,与业务写入原子提交(方案 §3.3);
 * 状态查询按业务单号的最新一条实例判定,已结束实例的结果从审批台账反推,不读引擎变量明细。
 */
public interface WorkflowApi {

    /**
     * 发起流程:业务单落库与流程发起写在同一个事务里,任一半失败就整体回滚。
     * 同一 businessKey 已有在途实例时直接拒绝——一单一条流程是业务口径,引擎不认识它。
     */
    StartResult start(WorkflowStartCommand command);

    /**
     * 按业务单撤销在途流程:引擎终止实例、在途待办随之消失,台账记一条撤销并派发结果回调。
     * 只撤在途;已结束(通过/驳回/已撤销)按 400 拒绝。
     */
    void withdraw(String businessType, String businessKey, String reason);

    /** 业务单当前的流程状态;从未进过流程返回 NONE */
    WorkflowStatus status(String businessType, String businessKey);

    /**
     * 发起结果:业务侧把 instanceId 存进自己的 flow_instance_id 列,列表页据此跳转轨迹而不查引擎。
     */
    record StartResult(
            /** 流程实例 ID */
            String instanceId,
            /** 实际命中的流程定义 ID(按 key 取的最新版本),排查"这单跑的是哪一版"用 */
            String definitionId) {
    }
}
