package com.snail.platform.function.monitor.diag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.monitor.diag.entity.SysMonitorDiagReport;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 职责:sys_monitor_diag_report 数据访问——写入只有体检落库(增)与清理任务分批删;
 * 查询侧按 trigger_time 过滤。本表在租户拦截器豁免清单。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:DiagnosticService、MonitorCleanupService。
 */
public interface SysMonitorDiagReportMapper extends BaseMapper<SysMonitorDiagReport> {

    /**
     * 分批删除过期报告(DD-19):@Delete + 绑定参数 LIMIT,不用 .last()。
     */
    @Delete("DELETE FROM sys_monitor_diag_report WHERE trigger_time < #{before} LIMIT #{limit}")
    int deleteExpiredBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
