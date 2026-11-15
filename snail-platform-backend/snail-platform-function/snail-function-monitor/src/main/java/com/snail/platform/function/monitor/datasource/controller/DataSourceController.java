package com.snail.platform.function.monitor.datasource.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.monitor.datasource.DataSourceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:数据源状态入口——只读快照,不触发外部调用。
 * 上游:监控前端顶栏状态芯片与抽屉;下游:DataSourceStatusService。
 * 口径:权限码 platform:monitor:overview(§5.6);凭据不回吐;baseUrl 只回 scheme+host+port。
 */
@Tag(name = "监控数据源状态")
@Validated
@RestController
@RequestMapping("/monitor/datasource")
public class DataSourceController {

    /** 数据源状态服务 */
    private final DataSourceStatusService statusService;

    public DataSourceController(DataSourceStatusService statusService) {
        this.statusService = statusService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:overview')")
    @Operation(summary = "数据源状态(metrics/logs/arthas/links)")
    @GetMapping("/status")
    public R<DataSourceStatusService.DataSourceStatusVO> status() {
        return R.ok(statusService.status());
    }
}
