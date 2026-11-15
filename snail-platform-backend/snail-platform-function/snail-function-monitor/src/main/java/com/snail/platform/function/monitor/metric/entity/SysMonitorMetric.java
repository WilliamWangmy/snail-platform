package com.snail.platform.function.monitor.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_monitor_metric 表 PO。指标采样只增;不继承审计基类(无修改无删除语义,清理任务物理删)。
 * 上游:MetricSampler 每 tick 逐条 insert;下游:MetricService.history 聚合查询、清理任务。
 * 租户拦截器豁免表,无租户列。
 */
@Getter
@Setter
@TableName("sys_monitor_metric")
public class SysMonitorMetric {

    /** 采样 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 实例标识:一期单实例固定 default */
    private String instance;

    /** 指标键:MetricKey 枚举值 */
    private String metric;

    /** 采样值,单位随指标键 */
    private Double value;

    /** 采样时间 */
    private LocalDateTime sampleTime;
}
