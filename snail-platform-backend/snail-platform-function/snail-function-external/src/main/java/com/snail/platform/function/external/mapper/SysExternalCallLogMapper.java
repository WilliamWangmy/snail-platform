package com.snail.platform.function.external.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.external.entity.SysExternalCallLog;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 职责:sys_external_call_log 数据访问。写入只有落账线程(增)与 ExternalCallLogService.clean 按保留期清历史(删),无更新路径;在租户拦截器 ignore 清单,
 * 异步落账线程不依赖线程租户上下文,租户归属靠行内快照列。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:ExternalCallLedger(写)、ExternalCallLogService(读)、ExternalCallStatQueryService(监控统计端口)。
 */
public interface SysExternalCallLogMapper extends BaseMapper<SysExternalCallLog> {

    /**
     * 按厂商聚合调用总数与失败数(ExternalCallStatPort.failureRateSince,诊断 external.deps 用):
     * 平台全量口径(本表在拦截器豁免清单,行内 tenant_id 快照不参与过滤);失败=status=1;
     * 按 fail 降序、vendor 升序,让最差厂商排前(消费方按阈值判定,排序只为可读)。
     */
    @Select("SELECT vendor AS provider, COUNT(*) AS total, "
            + "SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS fail "
            + "FROM sys_external_call_log "
            + "WHERE create_time >= #{since} "
            + "GROUP BY vendor ORDER BY fail DESC, vendor")
    List<VendorStatRow> selectVendorStatsSince(@Param("since") LocalDateTime since);

    /** 厂商聚合行:厂商标识、总数、失败数(MyBatis setter 映射) */
    class VendorStatRow {

        /** 厂商标识 */
        private String provider;

        /** 调用总数 */
        private Long total;

        /** 失败数(status=1) */
        private Long fail;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
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
