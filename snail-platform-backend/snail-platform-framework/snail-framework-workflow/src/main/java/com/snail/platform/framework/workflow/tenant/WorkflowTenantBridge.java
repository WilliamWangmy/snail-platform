package com.snail.platform.framework.workflow.tenant;

import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import java.util.function.Supplier;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Component;

/**
 * 职责:引擎线程的租户桥——把"流程实例属于哪个租户"反查出来,在该租户身份下执行回调。
 * 上游:Flowable 定时器/异步作业线程、以及任何由引擎发起而非请求链发起的业务回调;
 * 下游:TenantContextHolder.runAsEngineTenant(约定 §6 的第四类合法旁路,ArchUnit 锁调用点)。
 * 口径:这条路径没有登录人,操作人只能是 system,身份由调用方在回调里自带;
 * 租户 ID 一律从引擎反查,绝不接受调用方自选——自选等于绕过隔离(方案 §3.4)。
 * 约束:本类不声明事务,事务边界仍由业务 service 持有。
 */
@Component
public class WorkflowTenantBridge {

    /** 运行时服务:在途实例的租户从这里取 */
    private final RuntimeService runtimeService;

    /** 历史服务:已结束实例的回调仍要知道它当初属于谁 */
    private final HistoryService historyService;

    public WorkflowTenantBridge(RuntimeService runtimeService, HistoryService historyService) {
        this.runtimeService = runtimeService;
        this.historyService = historyService;
    }

    /** 在流程实例所属租户下执行一段动作,结束(含异常)必恢复原租户 */
    public <T> T runForInstance(String processInstanceId, Supplier<T> action) {
        return TenantContextHolder.runAsEngineTenant(tenantIdOfInstance(processInstanceId), action);
    }

    /**
     * 反查实例归属租户:先查运行时表,查不到再落历史表。
     * 两处都查不到即实例不存在——回调多半来晚了,按业务拒绝而不是静默跳过,
     * 静默跳过会让"审批已通过但业务单没改状态"这种半态无人发现。
     */
    private Long tenantIdOfInstance(String processInstanceId) {
        if (processInstanceId == null || processInstanceId.isBlank()) {
            throw BizException.badRequest("缺少流程实例标识,无法定位租户");
        }
        ProcessInstance running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (running != null) {
            return WorkflowTenantIds.toPlatformTenant(running.getTenantId());
        }
        HistoricProcessInstance finished = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (finished == null) {
            throw BizException.badRequest("流程实例不存在:" + processInstanceId);
        }
        return WorkflowTenantIds.toPlatformTenant(finished.getTenantId());
    }
}
