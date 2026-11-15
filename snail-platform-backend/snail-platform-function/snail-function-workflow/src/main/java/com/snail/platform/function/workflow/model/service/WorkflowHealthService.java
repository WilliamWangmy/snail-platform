package com.snail.platform.function.workflow.model.service;

import com.snail.platform.framework.workflow.engine.WorkflowEngineGateway;
import com.snail.platform.function.workflow.api.WorkflowHealthPort;
import org.springframework.stereotype.Service;

/**
 * 职责:WorkflowHealthPort 的 model 子域实现——流程引擎健康探针,委托引擎门面做只读统计。
 * 上游:WorkflowHealthPort 契约(monitor 诊断 flowable.engine 消费);下游:WorkflowEngineGateway。
 * 口径:只读,不触发部署/缓存;Flowable 词汇关在 framework-workflow,本类不出现引擎类型(方案 §2 边界);
 * 与 WorkflowModelService 同子域(model),不产生新的跨子域边。
 */
@Service
public class WorkflowHealthService implements WorkflowHealthPort {

    /** 引擎门面:唯一合法的引擎访问出口 */
    private final WorkflowEngineGateway gateway;

    public WorkflowHealthService(WorkflowEngineGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public long countProcessDefinitions() {
        return gateway.countProcessDefinitions();
    }
}
