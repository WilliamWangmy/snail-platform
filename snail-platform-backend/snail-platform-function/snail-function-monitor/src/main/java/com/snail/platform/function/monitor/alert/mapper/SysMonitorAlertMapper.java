package com.snail.platform.function.monitor.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlert;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 职责:sys_monitor_alert 数据访问——评估器按规则查开放告警(去重),查询侧状态/级别过滤,
 * 清理任务只删 resolved 且过期的行(firing/acked 永不删,DD-19 分批绑定 LIMIT)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:AlertEvaluator、AlertService、MonitorCleanupJob。
 */
public interface SysMonitorAlertMapper extends BaseMapper<SysMonitorAlert> {

    /** 该规则当前开放告警(firing/acked,最多 1 条——同规则去重键);无则 null */
    @Select("SELECT * FROM sys_monitor_alert WHERE rule_id = #{ruleId} AND status IN ('firing','acked') ORDER BY alert_id DESC LIMIT 1")
    SysMonitorAlert selectOpenByRuleId(@Param("ruleId") Long ruleId);

    /** 开放告警按级别计数(activeAlertCount):level → count */
    @Select("SELECT level, COUNT(*) AS cnt FROM sys_monitor_alert WHERE status IN ('firing','acked') GROUP BY level")
    List<Map<String, Object>> countActiveByLevel();

    /**
     * 分批删除已恢复且过期的告警(DD-19):firing/acked 永不删;绑定参数 LIMIT,不用 .last()。
     */
    @Delete("DELETE FROM sys_monitor_alert WHERE status = 'resolved' AND resolve_time < #{before} LIMIT #{limit}")
    int deleteResolvedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
