package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.diag.DiagCheck;
import com.snail.platform.function.workflow.api.WorkflowHealthPort;
import org.springframework.stereotype.Component;

/**
 * flowable.engine:流程定义计数查询;异常 fail、detail 写定义数;无 warn 档。
 */
@Component
public class FlowableEngineCheck implements DiagCheck {

    private final WorkflowHealthPort workflowHealthPort;

    public FlowableEngineCheck(WorkflowHealthPort workflowHealthPort) {
        this.workflowHealthPort = workflowHealthPort;
    }

    @Override
    public String key() {
        return "flowable.engine";
    }

    @Override
    public String name() {
        return "流程引擎";
    }

    @Override
    public Item run() {
        long start = System.nanoTime();
        try {
            long count = workflowHealthPort.countProcessDefinitions();
            return Item.pass(key(), name(), (System.nanoTime() - start) / 1_000_000,
                    "引擎可查,流程定义 " + count + " 条", null);
        } catch (Exception e) {
            return Item.fail(key(), name(), (System.nanoTime() - start) / 1_000_000,
                    "引擎查询失败: " + e.getMessage(),
                    "检查流程引擎配置与当前租户的引擎归属(缺少租户上下文也会拒绝)");
        }
    }
}
