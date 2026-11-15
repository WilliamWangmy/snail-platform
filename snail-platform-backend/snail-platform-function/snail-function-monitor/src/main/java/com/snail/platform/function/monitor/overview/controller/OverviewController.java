package com.snail.platform.function.monitor.overview.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.monitor.overview.dto.OverviewSummaryVO;
import com.snail.platform.function.monitor.overview.dto.TrendsVO;
import com.snail.platform.function.monitor.overview.service.OverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:总览入口——summary 契约全字段与登录趋势,只读。
 * 上游:监控前端总览页;下游:OverviewService。
 * 口径:权限码 platform:monitor:overview;days 1-30 校验在 controller 入口。
 */
@Tag(name = "监控总览")
@Validated
@RestController
@RequestMapping("/monitor/overview")
public class OverviewController {

    /** 总览聚合服务 */
    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:overview')")
    @Operation(summary = "总览概要(实例/健康/当前值/告警计数)")
    @GetMapping("/summary")
    public R<OverviewSummaryVO> summary() {
        return R.ok(overviewService.summary());
    }

    @PreAuthorize("hasAuthority('platform:monitor:overview')")
    @Operation(summary = "登录趋势与在线数")
    @GetMapping("/trends")
    public R<TrendsVO> trends(@RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 30) {
            days = 7;
        }
        return R.ok(overviewService.trends(days));
    }
}
