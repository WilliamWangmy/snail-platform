package com.snail.platform.function.workflow.support;

import com.snail.platform.function.workflow.api.WorkflowOutcome;
import com.snail.platform.function.workflow.api.WorkflowOutcomeListener;
import com.snail.platform.function.workflow.api.WorkflowStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 职责:流程结果回调的唯一出口——把"审批成立了"这件事告诉认领该业务类型的模块(方案 §7)。
 * 上游:task 域的办理收尾、instance 域的发起与撤销;下游:各业务模块的 WorkflowOutcomeListener。
 * 口径:
 * 1) 派发排在事务提交之后。流程动作与业务回写不是同一件事,审批已经成立,业务侧写坏了也不该把单子退回未办;
 * 2) outcome 在登记同步器之前构造好,回调里不再读线程上下文——afterCommit 跑在线程上下文清理的当口;
 * 3) 没有业务认领(businessType 为空或无人注册)就静默返回:平台直接发起的流程本就没有单据要回写;
 * 4) 监听器抛出的异常只落日志。补偿属业务侧与 P5 观测线的账,不在这里假装能救。
 */
@Component
public class WorkflowOutcomeDispatcher {

    /** 回调失败留痕:单据会停在旧状态,这条日志是唯一的线索 */
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowOutcomeDispatcher.class);

    /** 业务类型 -> 监听器;启动期归集,同一业务类型重复注册直接拒绝启动 */
    private final Map<String, WorkflowOutcomeListener> listeners;

    public WorkflowOutcomeDispatcher(List<WorkflowOutcomeListener> listenerBeans) {
        Map<String, WorkflowOutcomeListener> registered = new LinkedHashMap<>();
        for (WorkflowOutcomeListener listener : listenerBeans) {
            WorkflowOutcomeListener previous = registered.putIfAbsent(listener.businessType(), listener);
            if (previous != null) {
                throw new IllegalStateException("业务类型 " + listener.businessType()
                        + " 注册了两个流程结果监听器:" + previous.getClass().getName()
                        + " 与 " + listener.getClass().getName());
            }
        }
        this.listeners = Map.copyOf(registered);
    }

    /** 提交后派发流程结果;当前没有事务同步(例如集成测试直调)就地派发 */
    public void dispatch(String businessType, String businessKey, String instanceId, WorkflowStatus status) {
        if (businessType == null || businessType.isBlank()) {
            return;
        }
        WorkflowOutcomeListener listener = listeners.get(businessType);
        if (listener == null) {
            return;
        }
        WorkflowOutcome outcome = new WorkflowOutcome(businessType, businessKey, instanceId, status);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invoke(listener, outcome);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invoke(listener, outcome);
            }
        });
    }

    /** 派发一次结果;业务侧异常不外抛——流程已经提交,这里没有可回滚的东西 */
    private void invoke(WorkflowOutcomeListener listener, WorkflowOutcome outcome) {
        try {
            listener.onOutcome(outcome);
        } catch (RuntimeException e) {
            LOG.warn("流程结果回调失败,单据状态停在旧值:{}", outcome, e);
        }
    }
}
