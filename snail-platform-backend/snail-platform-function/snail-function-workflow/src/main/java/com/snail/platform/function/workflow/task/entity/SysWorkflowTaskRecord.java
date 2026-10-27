package com.snail.platform.function.workflow.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_workflow_task_record 表 PO(方案 §4.3)。平台视角的审批台账,只增不改不删。
 * 上游:task 子域的办理动作;下游:轨迹页与按人/按单查询。
 * 口径:不继承审计基类——留痕要复现"此刻此人",给它 update 列与假删除字段就是给篡改留门;
 * 操作人五列与目标人两列都是动作发生时的快照,用户后续改名、调岗、删号都不回溯改写。
 * 租户列由拦截器注入:留痕只在请求线程或事务提交回调里写,两种处境都有租户上下文。
 */
@Getter
@Setter
@TableName("sys_workflow_task_record")
public class SysWorkflowTaskRecord {

    /** 留痕 ID */
    @TableId(value = "record_id", type = IdType.AUTO)
    private Long recordId;

    /** 归属租户,拦截器注入与回读用,业务侧不手写 */
    @TableField("tenant_id")
    private Long tenantId;

    /** 流程实例 ID,引擎侧坐标 */
    private String processInstanceId;

    /** 引擎任务 ID;发起与撤销没有对应任务,此列为空 */
    private String taskId;

    /** 节点标识(BPMN userTask id) */
    private String taskDefinitionKey;

    /** 节点名称快照,设计器改名不回溯 */
    private String taskName;

    /** 业务域标签,与 businessKey 组成唯一关联键 */
    private String businessType;

    /** 业务单号 */
    private String businessKey;

    /** 动作:start / claim / approve / reject / transfer / withdraw */
    private String action;

    /** 操作人用户 ID */
    private Long operatorId;

    /** 操作人登录名快照:此刻为准 */
    private String operatorUsername;

    /** 操作人显示名快照 */
    private String operatorNickname;

    /** 操作人组织 ID 快照 */
    private Long operatorOrgId;

    /** 操作人组织名称快照:调岗与组织改名都不改写历史 */
    private String operatorOrgName;

    /** 转办目标用户 ID,仅 transfer 有值 */
    private Long targetUserId;

    /** 转办目标显示名快照 */
    private String targetUserNickname;

    /** 审批意见/撤销原因 */
    private String comment;

    /** 操作时刻(毫秒),台账时间轴 */
    private LocalDateTime createdAt;
}
