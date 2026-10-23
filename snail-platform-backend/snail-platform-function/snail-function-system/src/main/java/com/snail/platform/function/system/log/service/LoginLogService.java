package com.snail.platform.function.system.log.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.excel.ExcelExporter;
import com.snail.platform.framework.security.authority.AuthorityChecker;
import com.snail.platform.function.system.log.dto.LoginLogExportRow;
import com.snail.platform.function.system.log.dto.LoginLogQuery;
import com.snail.platform.function.system.log.dto.LoginLogVO;
import com.snail.platform.function.system.log.entity.SysLoginLog;
import com.snail.platform.function.system.log.mapper.SysLoginLogMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 职责:登录日志查询面——分页筛选(用户名/IP/结果/时间范围)与 xlsx 导出;写路径在 auth 的登录链,这里只读。
 * 租户钉死口径与 OperLogService 完全一致(ignore 表,边界只能由 Service 显式给):持有 platform:loginlog:all
 * 者可跨租户筛,其余锁本租户;tenant_id=0 的"归属不明失败"行只有平台侧看得见——它本就不属于任何租户。
 * 上游:LoginLogController;下游:SysLoginLogMapper、ExcelExporter。
 */
@Service
public class LoginLogService {

    /** 跨租户判定权限码,与操作日志同一口径 */
    private static final String PLATFORM_ALL = "platform:loginlog:all";

    /** 导出行数上限,超出拒绝 */
    private static final long EXPORT_ROW_LIMIT = 50_000L;

    /** 登录日志表只读 */
    private final SysLoginLogMapper loginLogMapper;

    public LoginLogService(SysLoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    public PageResult<LoginLogVO> page(LoginLogQuery query) {
        Page<SysLoginLog> page = PageAssembler.from(query);
        IPage<SysLoginLog> result = loginLogMapper.selectPage(page,
                buildWrapper(query).orderByDesc(SysLoginLog::getId));
        return PageResult.of(result.getRecords().stream().map(LoginLogVO::of).toList(), result.getTotal());
    }

    public void export(HttpServletResponse response, LoginLogQuery query) throws IOException {
        Long counted = loginLogMapper.selectCount(buildWrapper(query));
        if (counted > EXPORT_ROW_LIMIT) {
            throw BizException.badRequest("导出行数超过 5 万,请缩小时间范围");
        }
        List<SysLoginLog> rows = loginLogMapper.selectList(
                buildWrapper(query)
                        .orderByDesc(SysLoginLog::getId)
                        .last("LIMIT " + EXPORT_ROW_LIMIT));
        ExcelExporter.write(response, "登录日志", LoginLogExportRow.class,
                rows.stream().map(LoginLogExportRow::of).toList());
    }

    private LambdaQueryWrapper<SysLoginLog> buildWrapper(LoginLogQuery query) {
        Long pinned = isPlatform() ? query.tenantId() : TenantContextHolder.get();
        String username = query.username();
        String ip = query.ip();
        return Wrappers.<SysLoginLog>lambdaQuery()
                .eq(pinned != null, SysLoginLog::getTenantId, pinned)
                .like(notBlank(username), SysLoginLog::getUsername, username)
                .like(notBlank(ip), SysLoginLog::getIp, ip)
                .eq(query.status() != null, SysLoginLog::getStatus, query.status())
                .ge(query.beginTime() != null, SysLoginLog::getLoginTime, query.beginTime())
                .le(query.endTime() != null, SysLoginLog::getLoginTime, query.endTime());
    }

    private boolean isPlatform() {
        return AuthorityChecker.has(PLATFORM_ALL);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
