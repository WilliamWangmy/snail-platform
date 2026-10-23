package com.snail.platform.function.system.log.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.log.dto.LoginLogQuery;
import com.snail.platform.function.system.log.dto.LoginLogVO;
import com.snail.platform.function.system.log.service.LoginLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:登录日志查询入口——分页与导出;日志行由 auth 登录链写入,这里只读。
 * 上游:日志管理页面;下游:LoginLogService。tenantId 参数只对平台侧生效,钉死逻辑在 Service。
 */
@Tag(name = "登录日志")
@Validated
@RestController
@RequestMapping("/system/log/login")
public class LoginLogController {

    /** 登录日志查询服务 */
    private final LoginLogService loginLogService;

    public LoginLogController(LoginLogService loginLogService) {
        this.loginLogService = loginLogService;
    }

    @Operation(summary = "登录日志分页")
    @GetMapping("/page")
    public R<PageResult<LoginLogVO>> page(@Valid LoginLogQuery query) {
        return R.ok(loginLogService.page(query));
    }

    @Operation(summary = "导出登录日志 xlsx(超 5 万行拒)")
    @OperLog(module = "登录日志", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public void export(HttpServletResponse response, @Valid LoginLogQuery query) throws IOException {
        loginLogService.export(response, query);
    }
}
