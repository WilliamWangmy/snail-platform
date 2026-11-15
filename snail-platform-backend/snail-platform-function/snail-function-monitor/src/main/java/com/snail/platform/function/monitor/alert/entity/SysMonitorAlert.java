package com.snail.platform.function.monitor.alert.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_monitor_alert 表 PO。告警只增 + 状态流转(firing/acked/resolved);不继承审计基类
 * (确认人/操作人用显示名快照列,DD-15)。
 * 上游:AlertEvaluator(开新/更新/恢复)、AlertService(ack);下游:告警查询端点与清理任务。
 * 租户拦截器豁免表;notify_log 是 JSON 列,实体存 String,由服务层手工序列化(DD-08)。
 */
@Getter
@Setter
@TableName("sys_monitor_alert")
public class SysMonitorAlert {

    /** 告警 ID */
    @TableId(value = "alert_id", type = IdType.AUTO)
    private Long alertId;

    /** 规则 ID;规则删除后仍保留快照 */
    private Long ruleId;

    /** 规则名称快照 */
    private String ruleName;

    /** 指标键快照 */
    private String metric;

    /** 级别快照:info/warning/critical */
    private String level;

    /** 状态:firing/acked/resolved */
    private String status;

    /** 最近一次触发值 */
    private Double triggerValue;

    /** 阈值快照 */
    private Double threshold;

    /** 比较符快照:gt/gte/lt/lte/eq */
    private String comparator;

    /** 首次触发时间 */
    private LocalDateTime firstTime;

    /** 最近一次满足时间 */
    private LocalDateTime lastTime;

    /** 恢复时间;开放告警为 null */
    private LocalDateTime resolveTime;

    /** 确认人显示名快照 */
    private String ackBy;

    /** 确认时间 */
    private LocalDateTime ackTime;

    /** 通知记录 JSON:[{time,type,recipients,channels,error}];服务层手工序列化(DD-08) */
    private String notifyLog;
}
