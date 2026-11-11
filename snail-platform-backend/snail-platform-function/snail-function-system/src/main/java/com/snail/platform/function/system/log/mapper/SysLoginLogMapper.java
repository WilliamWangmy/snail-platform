package com.snail.platform.function.system.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 职责:sys_login_log 数据访问。只增;查询侧显式写 tenant_id 条件(本表在拦截器豁免清单)。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:认证链留痕与 log 子域查询、租户详情统计的分端回填。
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
}
