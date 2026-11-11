package com.snail.platform.function.storage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.storage.dto.StorageUsageVO;
import com.snail.platform.function.storage.entity.SysFile;
import com.snail.platform.function.storage.mapper.SysFileMapper;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 职责:存储配额的两段式判定——上传前无锁预检做快速拒绝,对象写完后在事务内锁租户行重算并入库。
 * 上游:FileStorageService.upload;下游:sys_tenant 行锁、sys_file 汇总与插入。
 * 口径:配额权威判定必须在锁住租户行之后重算,先 SUM 再插入会被并发上传绕过;
 * 事务只包住"锁行→汇总→插入",对象 IO 不在事务里,避免把网络写挂进数据库锁窗口。
 * 租户上下文缺失(平台侧或单元测试)按无配额主体处理,直接入库。
 */
@Service
public class FileQuotaService {

    /** 字节换算 MB 的基数 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 配额汇总列别名,selectObjs 取首个单元格 */
    private static final String USED_SIZE_SELECT = "COALESCE(SUM(`size`), 0) AS used_size";

    /** 配额不足的统一文案 */
    private static final String QUOTA_EXCEEDED = "租户存储配额不足";

    /** 文件元数据读写 */
    private final SysFileMapper fileMapper;

    /** 租户资料读:存储配额与行锁(登记例外,与 auth 同款只读边) */
    private final SysTenantMapper tenantMapper;

    public FileQuotaService(SysFileMapper fileMapper, SysTenantMapper tenantMapper) {
        this.fileMapper = fileMapper;
        this.tenantMapper = tenantMapper;
    }

    /** 上传前预检:不加锁,只挡明显超额的请求;并发竞态由 insertWithinQuota 兜住 */
    public void assertCapacity(long incomingSize) {
        Long tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            return;
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStorageLimitMb() == null) {
            return;
        }
        if (usedBytes() + incomingSize > limitBytes(tenant)) {
            throw BizException.badRequest(QUOTA_EXCEEDED);
        }
    }

    /**
     * 锁租户行后重算占用并插入元数据:同一租户的并发上传在此串行,
     * 任一请求拿锁时都能看到前一笔已提交的 size,配额不会被竞态突破。
     * REQUIRED:调用方(FileStorageService.upload)已有事务时加入同一事务,回滚一起回滚,
     * 不再出现"对象已写、元数据未落"的悬挂窗口。
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void insertWithinQuota(SysFile row) {
        Long tenantId = TenantContextHolder.get();
        if (tenantId != null) {
            SysTenant tenant = tenantMapper.selectOne(Wrappers.<SysTenant>lambdaQuery()
                    .eq(SysTenant::getTenantId, tenantId)
                    .last("FOR UPDATE"));
            if (tenant != null && tenant.getStorageLimitMb() != null
                    && usedBytes() + row.getSize() > limitBytes(tenant)) {
                throw BizException.badRequest(QUOTA_EXCEEDED);
            }
        }
        fileMapper.insert(row);
    }

    /** 全租户已用字节数;租户条件与逻辑删条件由拦截器自动注入 */
    private long usedBytes() {
        List<Object> totals = fileMapper.selectObjs(
                Wrappers.<SysFile>query().select(USED_SIZE_SELECT));
        Object first = totals.isEmpty() ? null : totals.get(0);
        return first instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 租户存储用量(平台侧详情统计,统计层设计 §5.5):总量 SUM + 文件数 COUNT 一条聚合,
     * 不按分类(ext)细分(2026-11-11 收窄口径)。
     * 平台侧变体必须 withoutTenant + 显式 eq(tenant_id)——调用方是平台管理员,上下文是其自身租户,
     * 拦截器注入会查成平台自己的数据(AC-9 专项);租户侧配额校验链(:47-59/:67-80)仍走拦截器口径不动,
     * 两个变体的 WHERE 条件完全一致(deleted=0 由 MP 逻辑删自动追加),统计与"上传被拒"同口径(D-05)。
     * 走 (tenant_id,...) 前缀索引(V2:30-32),千文件量级毫秒级(NFR-1)。
     */
    public StorageUsageVO usage(Long tenantId) {
        List<Map<String, Object>> rows = TenantContextHolder.withoutTenant(() -> fileMapper.selectMaps(
                Wrappers.<SysFile>query()
                        .select("COALESCE(SUM(`size`), 0) AS used_bytes", "COUNT(*) AS file_count")
                        // 聚合 select 需要原生列名,故用 QueryWrapper 而非 Lambda;eq 同步用字符串列名
                        .eq("tenant_id", tenantId)));
        Map<String, Object> agg = rows.isEmpty() ? Map.of() : rows.get(0);
        long usedBytes = agg.get("used_bytes") instanceof Number n ? n.longValue() : 0L;
        long fileCount = agg.get("file_count") instanceof Number n ? n.longValue() : 0L;
        // 配额取自 sys_tenant(豁免表,按 ID 定点取无租户条件问题);租户行缺失按不限兜底,
        // 存量令牌已被租户域作废,这里只是防御性出口
        SysTenant tenant = tenantMapper.selectById(tenantId);
        Long limitMb = tenant == null ? null : tenant.getStorageLimitMb();
        return new StorageUsageVO(limitMb, usedBytes, fileCount);
    }

    private static long limitBytes(SysTenant tenant) {
        return tenant.getStorageLimitMb() * BYTES_PER_MB;
    }
}
