package com.snail.platform.function.workflow.template.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_workflow_template 表 PO。平台维护的流程模板全局目录,与 sys_menu 同款口径:一份、无租户列。
 * 上游:framework-db 的 AuditedEntity;下游:template 子域的 mapper 与 service。
 * 口径:模板不带审批人绑定的正确性承诺(租户角色 ID 是租户内坐标,平台无法预绑),
 * 租户从模板建模型后必须在设计器里绑定本租户角色才能发布;template_key 全局唯一含已删记录,建后不可改。
 */
@Getter
@Setter
@TableName("sys_workflow_template")
public class SysWorkflowTemplate extends AuditedEntity {

    /** 模板 ID */
    @TableId(value = "template_id", type = IdType.AUTO)
    private Long templateId;

    /** 模板标识,建后不可改,全局唯一含已删记录 */
    private String templateKey;

    /** 模板名称 */
    private String name;

    /** 分类,展示与筛选用 */
    private String category;

    /** 备注:适用场景说明 */
    private String remark;

    /** BPMN XML 原文,租户建模型时作为初始草稿拷出并按租户 key 改名 */
    private String bpmnXml;

    /** 状态:0 上架(租户可选) 1 停用(不出现在租户模板列表) */
    private Integer status;
}
