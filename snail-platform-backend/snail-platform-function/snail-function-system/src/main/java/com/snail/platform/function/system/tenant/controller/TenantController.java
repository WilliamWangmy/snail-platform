package com.snail.platform.function.system.tenant.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.function.system.tenant.dto.TenantCreateRequest;
import com.snail.platform.function.system.tenant.dto.TenantQuery;
import com.snail.platform.function.system.tenant.dto.TenantUpdateRequest;
import com.snail.platform.function.system.tenant.dto.TenantVO;
import com.snail.platform.function.system.tenant.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:平台侧租户管理入口——列表、开通、资料修改、停用/启用;不提供删除(销户是运维动作,页面不给入口)。
 * 上游:平台管理端;下游:TenantService。四个端点全挂 platform:tenant:* 权限码,仅有平台角色显式授权者可达。
 */
@Tag(name = "租户管理")
@Validated
@RestController
@RequestMapping("/system/tenant")
public class TenantController {

    /** 租户业务服务 */
    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PreAuthorize("hasAuthority('platform:tenant:list')")
    @Operation(summary = "租户分页列表")
    @GetMapping("/page")
    public R<PageResult<TenantVO>> page(@Valid TenantQuery query) {
        return R.ok(tenantService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:tenant:create')")
    @Operation(summary = "开通租户(同事务复制根组织/内置角色/管理员模板)")
    @OperLog(module = "租户管理", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Long> create(@Valid @RequestBody TenantCreateRequest request) {
        return R.ok(tenantService.provision(request));
    }

    @PreAuthorize("hasAuthority('platform:tenant:update')")
    @Operation(summary = "修改租户资料")
    @OperLog(module = "租户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{tenantId}")
    public R<Void> update(@PathVariable Long tenantId, @Valid @RequestBody TenantUpdateRequest request) {
        tenantService.update(tenantId, request);
        return R.ok(null);
    }

    @PreAuthorize("hasAuthority('platform:tenant:update')")
    @Operation(summary = "停用/启用租户")
    @OperLog(module = "租户管理", businessType = BusinessType.UPDATE)
    @PutMapping("/{tenantId}/status")
    public R<Void> changeStatus(@PathVariable Long tenantId,
                                       @RequestParam @Min(value = 0, message = "状态取值 0/1")
                                       @Max(value = 1, message = "状态取值 0/1") int status) {
        tenantService.changeStatus(tenantId, status);
        return R.ok(null);
    }
}
