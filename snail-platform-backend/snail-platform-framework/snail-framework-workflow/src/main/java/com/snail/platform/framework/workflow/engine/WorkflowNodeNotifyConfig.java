package com.snail.platform.framework.workflow.engine;

import java.util.List;
import java.util.Set;

/**
 * 节点通知配置快照:取自用户任务节点的 BPMN 扩展属性(设计器面板落盘,方案 D9)。
 * 定义不可变(每次部署新定义),网关按 definitionId+节点缓存后不再回查引擎。
 * 上游:WorkflowEngineGateway.nodeNotifyConfig;下游:function-workflow 的到达提醒与抄送扇出。
 */
public record WorkflowNodeNotifyConfig(
        /** 到达提醒通道名集合(inner/mail/sms,逗号串拆分),未配置为空集 */
        Set<String> notifyTypes,
        /** 抄送用户 ID 字符串集合 */
        List<String> ccUsers,
        /** 抄送角色 ID 字符串集合 */
        List<String> ccGroups) {

    /** 空配置:节点不存在/非用户任务/未配置任何通知属性时统一返回,调用方按"无配置"降级 */
    public static WorkflowNodeNotifyConfig empty() {
        return new WorkflowNodeNotifyConfig(Set.of(), List.of(), List.of());
    }
}
