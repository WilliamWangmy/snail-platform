package com.snail.platform.function.monitor.log.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.monitor.log.dto.AppLogDetailQuery;
import com.snail.platform.function.monitor.log.dto.AppLogDetailVO;
import com.snail.platform.function.monitor.log.dto.AppLogPageQuery;
import com.snail.platform.function.monitor.log.dto.AppLogPageVO;
import com.snail.platform.function.monitor.log.dto.LogFilesVO;
import com.snail.platform.function.monitor.log.dto.LogLineVO;
import com.snail.platform.function.monitor.log.dto.TailQuery;
import com.snail.platform.function.monitor.log.service.AppLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:应用日志检索入口——files/page/tail/detail 四端点(主方案 §5.3 契约),只读。
 * 上游:监控前端日志页;下游:AppLogService。
 * 口径:权限码 platform:monitor:log;时间窗跨度/limit 校验在 service;tail 的 since 为 GET 散参
 * (2 个,不触发 ArchUnit 散参规则);detail 的 lineNo 允许前端传浮点(收敛为 long)。
 */
@Tag(name = "监控应用日志")
@Validated
@RestController
@RequestMapping("/monitor/log/app")
public class AppLogController {

    /** 日志检索服务 */
    private final AppLogService appLogService;

    public AppLogController(AppLogService appLogService) {
        this.appLogService = appLogService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:log')")
    @Operation(summary = "日志文件枚举")
    @GetMapping("/files")
    public R<LogFilesVO> files() {
        return R.ok(appLogService.files());
    }

    @PreAuthorize("hasAuthority('platform:monitor:log')")
    @Operation(summary = "日志检索(时间窗最后 limit 条,时间倒序)")
    @GetMapping("/page")
    public R<AppLogPageVO> page(@Valid AppLogPageQuery query) {
        return R.ok(appLogService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:monitor:log')")
    @Operation(summary = "增量 tail(since 之后,正序)")
    @GetMapping("/tail")
    public R<List<LogLineVO>> tail(@RequestParam Long since,
                                   @RequestParam(required = false) Integer limit) {
        return R.ok(appLogService.tail(new TailQuery(since, limit)));
    }

    @PreAuthorize("hasAuthority('platform:monitor:log')")
    @Operation(summary = "行上下文(仅 file 模式)")
    @GetMapping("/detail")
    public R<AppLogDetailVO> detail(@RequestParam String fileId,
                                    @RequestParam(required = false) Double lineNo,
                                    @RequestParam(required = false) Integer context) {
        AppLogDetailQuery query = new AppLogDetailQuery(fileId,
                lineNo == null ? null : lineNo.longValue(), context);
        return R.ok(appLogService.detail(query));
    }
}
