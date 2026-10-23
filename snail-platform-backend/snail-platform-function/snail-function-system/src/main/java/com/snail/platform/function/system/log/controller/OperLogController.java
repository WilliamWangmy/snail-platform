package com.snail.platform.function.system.log.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.log.dto.OperLogDetailVO;
import com.snail.platform.function.system.log.dto.OperLogListVO;
import com.snail.platform.function.system.log.dto.OperLogQuery;
import com.snail.platform.function.system.log.service.OperLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:操作日志查询入口——分页、详情、导出;本模块只读不写,没有任何修改性端点。
 * 上游:日志管理页面;下游:OperLogService。租户钉死在 Service,这里连 tenantId 都只透传平台侧参数。
 * 打标口径:导出自身是操作(businessType=EXPORT);读端点不打标。
 */
@Tag(name = "操作日志")
@Validated
@RestController
@RequestMapping("/system/log/oper")
public class OperLogController {

    /** 操作日志查询服务 */
    private final OperLogService operLogService;

    public OperLogController(OperLogService operLogService) {
        this.operLogService = operLogService;
    }

    @PreAuthorize("hasAuthority('system:operlog:list')")
    @Operation(summary = "操作日志分页")
    @GetMapping("/page")
    public R<PageResult<OperLogListVO>> page(@Valid OperLogQuery query) {
        return R.ok(operLogService.page(query));
    }

    @PreAuthorize("hasAuthority('system:operlog:list')")
    @Operation(summary = "操作日志详情(param/result 全文)")
    @GetMapping("/{id}")
    public R<OperLogDetailVO> detail(@PathVariable Long id) {
        return R.ok(operLogService.detail(id));
    }

    @PreAuthorize("hasAuthority('system:operlog:export')")
    @Operation(summary = "导出操作日志 xlsx(超 5 万行拒)")
    @OperLog(module = "操作日志", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public void export(HttpServletResponse response, @Valid OperLogQuery query) throws IOException {
        operLogService.export(response, query);
    }
}
