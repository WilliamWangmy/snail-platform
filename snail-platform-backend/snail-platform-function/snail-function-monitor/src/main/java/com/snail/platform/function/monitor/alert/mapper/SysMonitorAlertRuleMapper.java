package com.snail.platform.function.monitor.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.monitor.alert.entity.SysMonitorAlertRule;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 职责:sys_monitor_alert_rule 数据访问——规则 CRUD 走 MP 通用方法;静默是唯一 UPDATE 口径;
 * 清理只针对指标/告警/报告三表(规则永久保留,无清理)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:AlertRuleService、AlertEvaluator、AlertService。
 */
public interface SysMonitorAlertRuleMapper extends BaseMapper<SysMonitorAlertRule> {

    /** 覆盖式静默:silence_until = now + minutes(取该告警所属规则) */
    @Update("UPDATE sys_monitor_alert_rule SET silence_until = #{silenceUntil} WHERE rule_id = #{ruleId}")
    int updateSilenceUntil(@Param("ruleId") Long ruleId, @Param("silenceUntil") LocalDateTime silenceUntil);
}
