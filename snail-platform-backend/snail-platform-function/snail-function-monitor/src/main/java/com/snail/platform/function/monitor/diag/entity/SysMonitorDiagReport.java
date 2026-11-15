package com.snail.platform.function.monitor.diag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_monitor_diag_report 表 PO。诊断报告只增(一次体检一条);不继承审计基类
 * (触发人用显示名快照列,DD-15)。
 * 上游:DiagnosticService(体检完成 insert);下游:报告查询端点与清理任务。
 * 租户拦截器豁免表;items 是 JSON 列,实体存 String,由服务层手工序列化(DD-08)。
 */
@Getter
@Setter
@TableName("sys_monitor_diag_report")
public class SysMonitorDiagReport {

    /** 报告 ID */
    @TableId(value = "report_id", type = IdType.AUTO)
    private Long reportId;

    /** 触发人显示名快照 */
    private String triggerBy;

    /** 触发时间 */
    private LocalDateTime triggerTime;

    /** 总耗时(毫秒) */
    private Long costMs;

    /** 总体结论:pass/warn/fail */
    private String overall;

    /** 检查项结果 JSON:[{key,name,status,latencyMs,detail,suggestion}](服务层手工序列化) */
    private String items;
}
