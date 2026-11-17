package com.snail.platform.function.system.log.controller;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.log.dto.LogCleanRequest;
import com.snail.platform.function.system.log.dto.LoginLogQuery;
import com.snail.platform.function.system.log.dto.LoginLogVO;
import com.snail.platform.function.system.log.service.LoginLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:登录日志入口——分页、导出与历史清除;日志行由 auth 登录链写入,查询侧只读,唯一写端点按保留期清历史。
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

    @PreAuthorize("hasAuthority('system:loginlog:list')")
    @Operation(summary = "登录日志分页")
    @GetMapping("/page")
    public R<PageResult<LoginLogVO>> page(@Valid LoginLogQuery query) {
        return R.ok(loginLogService.page(query));
    }

    /**
     * 本人登录日志分页(个人中心):userId 恒来自登录上下文,只看自己;
     * 与 /page 不同,这里不要求日志管理权限码——看自己的留痕对"改自己"没有增量价值(ProfileController 同口径)。
     */
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "本人登录日志分页(只看自己,查询参数仅分页生效)")
    @GetMapping("/my")
    public R<PageResult<LoginLogVO>> my(@Valid LoginLogQuery query) {
        return R.ok(loginLogService.pageMine(query, LoginUserContextHolder.userId()));
    }

    @PreAuthorize("hasAuthority('system:loginlog:export')")
    @Operation(summary = "导出登录日志 xlsx(超 5 万行拒)")
    @OperLog(module = "登录日志", businessType = BusinessType.EXPORT)
    @GetMapping("/export")
    public void export(HttpServletResponse response, @Valid LoginLogQuery query) throws IOException {
        loginLogService.export(response, query);
    }

    @PreAuthorize("hasAuthority('system:loginlog:clean')")
    @Operation(summary = "清除历史登录日志(1m/6m/1y 三档保留期,不可逆)")
    @OperLog(module = "登录日志", businessType = BusinessType.DELETE)
    @PostMapping("/clean")
    public R<Long> clean(@Valid @RequestBody LogCleanRequest request) {
        return R.ok(loginLogService.clean(request));
    }
}
