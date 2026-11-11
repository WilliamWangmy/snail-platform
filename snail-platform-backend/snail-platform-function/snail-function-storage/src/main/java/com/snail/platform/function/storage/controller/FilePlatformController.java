package com.snail.platform.function.storage.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.storage.dto.FileQuery;
import com.snail.platform.function.storage.dto.FileVO;
import com.snail.platform.function.storage.dto.StorageUsageVO;
import com.snail.platform.function.storage.service.FileQuotaService;
import com.snail.platform.function.storage.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:平台文件入口——跨租户文件检索与租户存储用量统计,平台管理动作的读侧豁免(withoutTenant 窄窗口,
 * TenantContextHolder 登记的第三类路径)。
 * 上游:平台文件页面与租户详情抽屉;下游:FileStorageService.platformPage、FileQuotaService.usage。
 */
@Tag(name = "平台文件")
@RestController
@RequestMapping("/platform/file")
public class FilePlatformController {

    /** 文件服务 */
    private final FileStorageService fileStorageService;

    /** 存储配额服务:租户存储用量的聚合出口(与配额校验同口径,D-05) */
    private final FileQuotaService fileQuotaService;

    public FilePlatformController(FileStorageService fileStorageService, FileQuotaService fileQuotaService) {
        this.fileStorageService = fileStorageService;
        this.fileQuotaService = fileQuotaService;
    }

    @PreAuthorize("hasAuthority('platform:file:list')")
    @Operation(summary = "文件分页(跨租户)")
    @GetMapping("/page")
    public R<PageResult<FileVO>> page(@Valid FileQuery query) {
        return R.ok(fileStorageService.platformPage(query));
    }

    @PreAuthorize("hasAuthority('platform:tenant:list')")
    @Operation(summary = "租户存储用量(总配额/已用/文件数,租户详情抽屉存储总览;权限挂租户读面,与详情统计同面)")
    @GetMapping("/tenant-usage/{tenantId}")
    public R<StorageUsageVO> tenantUsage(@PathVariable Long tenantId) {
        return R.ok(fileQuotaService.usage(tenantId));
    }
}
