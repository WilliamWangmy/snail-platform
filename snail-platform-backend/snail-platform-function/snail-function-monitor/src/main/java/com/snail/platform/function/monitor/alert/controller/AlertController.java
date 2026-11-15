package com.snail.platform.function.monitor.alert.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.monitor.alert.dto.AlertDetailVO;
import com.snail.platform.function.monitor.alert.dto.AlertQuery;
import com.snail.platform.function.monitor.alert.dto.AlertVO;
import com.snail.platform.function.monitor.alert.dto.SilenceRequest;
import com.snail.platform.function.monitor.alert.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:告警查询与操作入口——active/page/详情查看(alert 码),ack(ack 码)与 silence(silence 码)写路径留痕。
 * 上游:监控前端告警页;下游:AlertService。
 * 口径:权限码三层分离(查看/确认/静默,§5.6);静默入口只挂告警行(DD-20)。
 */
@Tag(name = "监控告警")
@Validated
@RestController
@RequestMapping("/monitor/alert")
public class AlertController {

    /** 告警服务 */
    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:alert')")
    @Operation(summary = "当前开放告警(firing/acked)")
    @GetMapping("/active")
    public R<List<AlertVO>> active() {
        return R.ok(alertService.active());
    }

    @PreAuthorize("hasAuthority('platform:monitor:alert')")
    @Operation(summary = "告警历史分页")
    @GetMapping("/page")
    public R<PageResult<AlertVO>> page(@Valid AlertQuery query) {
        return R.ok(alertService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:monitor:alert')")
    @Operation(summary = "告警详情(规则快照+触发序列+通知记录)")
    @GetMapping("/{alertId}")
    public R<AlertDetailVO> detail(@PathVariable Long alertId) {
        return R.ok(alertService.detail(alertId));
    }

    @PreAuthorize("hasAuthority('platform:monitor:ack')")
    @Operation(summary = "确认告警(幂等)")
    @OperLog(module = "监控中心", businessType = BusinessType.UPDATE)
    @PostMapping("/{alertId}/ack")
    public R<Void> ack(@PathVariable Long alertId) {
        alertService.ack(alertId);
        return R.ok();
    }

    @PreAuthorize("hasAuthority('platform:monitor:silence')")
    @Operation(summary = "静默告警(覆盖式写规则 silence_until)")
    @OperLog(module = "监控中心", businessType = BusinessType.UPDATE)
    @PostMapping("/{alertId}/silence")
    public R<LocalDateTime> silence(@PathVariable Long alertId, @Valid @RequestBody SilenceRequest request) {
        return R.ok(alertService.silence(alertId, request.minutes()));
    }
}
