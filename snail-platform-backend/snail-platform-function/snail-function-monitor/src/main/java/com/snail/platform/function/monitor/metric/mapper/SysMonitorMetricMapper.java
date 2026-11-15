package com.snail.platform.function.monitor.metric.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.monitor.metric.entity.SysMonitorMetric;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 职责:sys_monitor_metric 数据访问——写入只有采样落库(增)与清理任务分批删(DD-19,绑定参数 LIMIT),
 * 查询侧按 metric + sample_time 聚合。本表在租户拦截器豁免清单,SQL 不受租户改写影响。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:MetricSampler(写)、MetricService(聚合读)、MonitorCleanupJob(删)。
 */
public interface SysMonitorMetricMapper extends BaseMapper<SysMonitorMetric> {

    /**
     * 时间桶平均聚合(history 端点):FLOOR(epoch 秒 / step) 为桶,t = bucket * 1000 还原 epochMs。
     */
    @Select("SELECT FLOOR(UNIX_TIMESTAMP(sample_time) / #{step}) * #{step} AS bucket, AVG(value) AS value "
            + "FROM sys_monitor_metric "
            + "WHERE metric = #{metric} AND sample_time >= #{begin} AND sample_time <= #{end} "
            + "GROUP BY bucket ORDER BY bucket")
    List<MetricBucketRow> aggregateByStep(@Param("metric") String metric,
                                          @Param("begin") LocalDateTime begin,
                                          @Param("end") LocalDateTime end,
                                          @Param("step") long step);

    /** 时间窗内原始点数(2000 点保护的前置计数) */
    @Select("SELECT COUNT(*) FROM sys_monitor_metric "
            + "WHERE metric = #{metric} AND sample_time >= #{begin} AND sample_time <= #{end}")
    long countByRange(@Param("metric") String metric,
                      @Param("begin") LocalDateTime begin,
                      @Param("end") LocalDateTime end);

    /**
     * 分批删除过期采样(DD-19):@Delete + 绑定参数 LIMIT(MySQL 预处理形式,不使用 .last(),不扩白名单)。
     */
    @Delete("DELETE FROM sys_monitor_metric WHERE sample_time < #{before} LIMIT #{limit}")
    int deleteExpiredBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /**
     * 聚合行投影:bucket(epoch 秒)、value(桶平均)。
     */
    record MetricBucketRow(
            /** 时间桶(epoch 秒) */
            Long bucket,
            /** 桶平均值 */
            Double value) {
    }
}
