package com.snail.platform.function.monitor.metric.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.monitor.metric.dto.HistoryVO;
import com.snail.platform.function.monitor.metric.dto.MetricHistoryQuery;
import com.snail.platform.function.monitor.metric.dto.RealtimeVO;
import com.snail.platform.function.monitor.metric.service.MetricService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:指标查询入口——实时(环形缓冲)与历史(本地聚合),只读。
 * 上游:监控前端总览页;下游:MetricService。
 * 口径:权限码 platform:monitor:overview(主方案 §5.6);跨度/step/2000 点保护校验在 service。
 */
@Tag(name = "监控指标")
@Validated
@RestController
@RequestMapping("/monitor/metrics")
public class MetricController {

    /** 指标查询服务 */
    private final MetricService metricService;

    public MetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:overview')")
    @Operation(summary = "实时曲线(环形缓冲)")
    @GetMapping("/realtime")
    public R<RealtimeVO> realtime(@RequestParam(defaultValue = "60") int minutes) {
        return R.ok(metricService.realtime(minutes));
    }

    @PreAuthorize("hasAuthority('platform:monitor:overview')")
    @Operation(summary = "历史曲线(DB 聚合)")
    @GetMapping("/history")
    public R<HistoryVO> history(@Valid MetricHistoryQuery query) {
        return R.ok(metricService.history(query));
    }
}
