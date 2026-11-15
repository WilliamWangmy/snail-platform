package com.snail.platform.function.monitor.alert.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.monitor.alert.dto.RuleCreateRequest;
import com.snail.platform.function.monitor.alert.dto.RuleQuery;
import com.snail.platform.function.monitor.alert.dto.RuleToggleRequest;
import com.snail.platform.function.monitor.alert.dto.RuleUpdateRequest;
import com.snail.platform.function.monitor.alert.dto.RuleVO;
import com.snail.platform.function.monitor.alert.service.AlertRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:告警规则入口——分页查看(alert 码)与增删改/启停(rule 码),写路径留 @OperLog。
 * 上游:监控前端告警页规则 tab;下游:AlertRuleService。
 * 口径:权限码分「查看/写」两层(§5.6);内置规则限制在 service 抛 400。
 */
@Tag(name = "监控告警规则")
@Validated
@RestController
@RequestMapping("/monitor/alert/rule")
public class AlertRuleController {

    /** 规则服务 */
    private final AlertRuleService alertRuleService;

    public AlertRuleController(AlertRuleService alertRuleService) {
        this.alertRuleService = alertRuleService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:alert')")
    @Operation(summary = "规则分页")
    @GetMapping("/page")
    public R<PageResult<RuleVO>> page(@Valid RuleQuery query) {
        return R.ok(alertRuleService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:monitor:rule')")
    @Operation(summary = "创建规则")
    @OperLog(module = "监控中心", businessType = BusinessType.INSERT)
    @PostMapping
    public R<RuleVO> create(@Valid @RequestBody RuleCreateRequest request) {
        return R.ok(alertRuleService.create(request));
    }

    @PreAuthorize("hasAuthority('platform:monitor:rule')")
    @Operation(summary = "修改规则")
    @OperLog(module = "监控中心", businessType = BusinessType.UPDATE)
    @PutMapping("/{ruleId}")
    public R<RuleVO> update(@PathVariable Long ruleId, @Valid @RequestBody RuleUpdateRequest request) {
        return R.ok(alertRuleService.update(ruleId, request));
    }

    @PreAuthorize("hasAuthority('platform:monitor:rule')")
    @Operation(summary = "删除规则(内置拒删)")
    @OperLog(module = "监控中心", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ruleId}")
    public R<Void> delete(@PathVariable Long ruleId) {
        alertRuleService.delete(ruleId);
        return R.ok();
    }

    @PreAuthorize("hasAuthority('platform:monitor:rule')")
    @Operation(summary = "规则启停(0 启用/1 停用)")
    @OperLog(module = "监控中心", businessType = BusinessType.UPDATE)
    @PutMapping("/{ruleId}/enabled")
    public R<Void> toggle(@PathVariable Long ruleId, @Valid @RequestBody RuleToggleRequest request) {
        alertRuleService.toggle(ruleId, request);
        return R.ok();
    }
}
