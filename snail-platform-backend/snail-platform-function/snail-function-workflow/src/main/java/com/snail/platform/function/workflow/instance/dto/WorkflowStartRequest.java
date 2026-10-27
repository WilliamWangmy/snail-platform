package com.snail.platform.function.workflow.instance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 职责:直接发起一条流程的入参——没有业务单据入口时(运维补单、演示、联调)用它。
 * 上游:POST /workflow/instance;下游:WorkflowInstanceService 转成 WorkflowStartCommand 走同一条发起链路。
 * 口径:业务模块不要从这里发起,它们实现 WorkflowApi.start,单据与流程必须同事务落库;
 * businessType/businessKey 留空即"裸流程",不挂单据也就没有结果回调,台账里两列为 null 是既定形态。
 * variables 只收标量,网关还会再校验一次并剔除 initiator。
 */
public record WorkflowStartRequest(
        /** 流程标识(模型 key,等于 BPMN 里的 process id) */
        @NotBlank(message = "请选择要发起的流程")
        @Size(max = 128, message = "流程标识不能超过 128 字")
        String definitionKey,
        /** 业务域标签,与单据表约定同值;裸流程留空 */
        @Size(max = 64, message = "业务类型不能超过 64 字")
        String businessType,
        /** 业务单号;留空表示不挂单据 */
        @Size(max = 64, message = "业务单号不能超过 64 字")
        String businessKey,
        /** 单据标题,落进实例名;留空退回流程定义名 */
        @Size(max = 200, message = "标题不能超过 200 字")
        String businessTitle,
        /** 流程变量,只准标量 */
        Map<String, Object> variables) {
}
