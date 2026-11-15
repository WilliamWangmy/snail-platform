package com.snail.platform.function.monitor.alert.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_monitor_alert_rule 表 PO。规则是配置数据:物理删除(DD-14),审计四列手写
 * 按 @TableField(fill) 由 SnailAuditMetaObjectHandler 填充,不继承 AuditedEntity(无逻辑删除列)。
 * 上游:AlertRuleService(CRUD)、AlertEvaluator(只读启用规则);下游:告警评估与前端规则管理。
 * 口径:enabled 0=启用/1=停用(平台口径);is_system=1 的内置规则只可改阈值/持续/级别/启停;
 * silence_until 期内不新开告警、不发任何通知(DD-16)。
 */
@Getter
@Setter
@TableName("sys_monitor_alert_rule")
public class SysMonitorAlertRule {

    /** 规则 ID */
    @TableId(value = "rule_id", type = IdType.AUTO)
    private Long ruleId;

    /** 规则名称,全局唯一(uk_mar_name;物理删后释放) */
    private String name;

    /** 指标键:MetricKey 枚举值 */
    private String metric;

    /** 比较符:gt/gte/lt/lte/eq */
    private String comparator;

    /** 阈值 */
    private Double threshold;

    /** 连续满足的采样点数才触发,1-10 */
    private Integer durationPoints;

    /** 级别:info/warning/critical */
    private String level;

    /** 开关:0 启用 1 停用 */
    private Integer enabled;

    /** 内置规则:0 否 1 是 */
    private Integer isSystem;

    /** 静默截止时间;null 为未静默 */
    private LocalDateTime silenceUntil;

    /** 备注 */
    private String remark;

    /** 创建人用户 ID(审计列手写) */
    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    /** 创建时间(审计列手写) */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 修改人用户 ID(审计列手写) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /** 修改时间(审计列手写) */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
