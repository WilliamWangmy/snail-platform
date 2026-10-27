package com.snail.platform.function.workflow.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_workflow_model 表 PO。流程模型归租户所有,同一行里共存"设计器草稿"与"当前发布指针"。
 * 上游:framework-db 的 TenantAuditedEntity(租户拦截器自动圈定本租户);下游:model 子域的 mapper 与 service。
 * 口径:model_key 即 BPMN 里的 process id,建后不可改,逻辑删除后仍占唯一键(租户内终身占用);
 * 三个指针列(deployment_id/process_definition_id/deployed_version)只由发布动作整体改写,别处只读。
 */
@Getter
@Setter
@TableName("sys_workflow_model")
public class SysWorkflowModel extends TenantAuditedEntity {

    /** 模型 ID */
    @TableId(value = "model_id", type = IdType.AUTO)
    private Long modelId;

    /** 流程标识,等于 BPMN 的 process id;建后不可改 */
    private String modelKey;

    /** 流程名称,界面展示用 */
    private String name;

    /** 分类,展示与筛选用 */
    private String category;

    /** 备注 */
    private String remark;

    /** 状态:0 草稿 1 已发布 2 停用 */
    private Integer status;

    /** 设计器草稿原文,与已发布版本分离 */
    private String draftXml;

    /** 来源模板 ID,仅溯源展示 */
    private Long templateId;

    /** 当前发布指针:引擎 deployment id */
    private String deploymentId;

    /** 当前发布指针:引擎流程定义 id */
    private String processDefinitionId;

    /** 当前发布指针:引擎侧版本号 */
    private Integer deployedVersion;

    /** 最后一次发布人用户 ID */
    private Long publishedBy;

    /** 最后一次发布时间 */
    private LocalDateTime publishTime;
}
