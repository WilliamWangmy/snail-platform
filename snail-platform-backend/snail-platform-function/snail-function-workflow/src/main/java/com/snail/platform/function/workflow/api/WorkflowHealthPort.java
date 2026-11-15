package com.snail.platform.function.workflow.api;

/**
 * 职责:流程引擎健康只读端口——跨模块探活 Flowable 引擎的唯一合法出口,跨模块不得触达引擎与 model 子域内部。
 * 上游:monitor(诊断 flowable.engine 检查项);下游:model 子域实现(WorkflowHealthService,经 WorkflowEngineGateway)。
 * 口径:只读统计,不触发部署/缓存;引擎租户取当前上下文(平台运维探活时即登录人租户);
 * 引擎不可用时照 gateway 口径抛业务拒绝,由消费方(诊断检查项)转为 fail。
 */
public interface WorkflowHealthPort {

    /** 当前租户的流程定义数量;引擎不可用抛业务拒绝,定义数为诊断 detail 的展示值 */
    long countProcessDefinitions();
}
