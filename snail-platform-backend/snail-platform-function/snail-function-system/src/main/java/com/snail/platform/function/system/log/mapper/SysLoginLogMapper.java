package com.snail.platform.function.system.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 职责:sys_login_log 数据访问。写入只有认证链留痕(增)与 log 子域按保留期清历史(删),无更新路径;查询侧显式写 tenant_id 条件(本表在拦截器豁免清单)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:认证链留痕与 log 子域查询、租户详情统计的分端回填、LoginLogStatService(监控统计端口)。
 */
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {

    /**
     * 分端登录用户回填查询(统计层设计 §5.6 lazy 自愈):某 scene 的 Valkey 统计键丢失(SCARD=0)时,
     * 以 login_log 为事实源取该租户该端的登录用户集合,读侧 SADD 回填后再读,累计数不清零。
     * 走 (tenant_id, username) 前缀索引(tenant_id 前缀过滤);仅键丢失后首次发生,常驻读路径不走这条 SQL。
     * 本表在拦截器豁免清单,原生 @Select 不受租户改写影响,tenant_id 显式入参。
     */
    @Select("SELECT DISTINCT user_id FROM sys_login_log "
            + "WHERE tenant_id = #{tenantId} AND scene = #{scene} "
            + "AND status = 1 AND user_id IS NOT NULL")
    List<Long> selectDistinctUserIds(@Param("tenantId") Long tenantId, @Param("scene") String scene);

    /**
     * 按自然日聚合登录总数与失败数(LoginStatPort.dailyCounts):平台全量口径,
     * 不带 tenant_id 条件(监控平台口径,§2.5);login_time 走索引过滤,左闭右开 [begin, end)。
     */
    @Select("SELECT DATE(login_time) AS day, COUNT(*) AS total, "
            + "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS fail "
            + "FROM sys_login_log "
            + "WHERE login_time >= #{begin} AND login_time < #{end} "
            + "GROUP BY DATE(login_time) ORDER BY day")
    List<DailyCountRow> selectDailyCounts(@Param("begin") LocalDate begin, @Param("end") LocalDate end);

    /** 自指定时刻起(含)的失败登录总数(LoginStatPort.failCountSince,login.fail.5m 键) */
    @Select("SELECT COUNT(*) FROM sys_login_log WHERE status = 0 AND login_time >= #{since}")
    long countFailsSince(@Param("since") LocalDateTime since);

    /** 日聚合行:按自然日统计登录总数与失败数(MyBatis setter 映射) */
    class DailyCountRow {

        /** 统计日 */
        private LocalDate day;

        /** 登录总数 */
        private Long total;

        /** 失败数(status=0) */
        private Long fail;

        public LocalDate getDay() {
            return day;
        }

        public void setDay(LocalDate day) {
            this.day = day;
        }

        public Long getTotal() {
            return total;
        }

        public void setTotal(Long total) {
            this.total = total;
        }

        public Long getFail() {
            return fail;
        }

        public void setFail(Long fail) {
            this.fail = fail;
        }
    }
}
