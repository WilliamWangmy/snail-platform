package com.snail.platform.function.storage.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.storage.dto.FileQuery;
import com.snail.platform.function.storage.dto.FileVO;
import com.snail.platform.function.storage.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:平台文件入口——跨租户文件检索,平台管理动作的读侧豁免(withoutTenant 窄窗口,
 * TenantContextHolder 登记的第三类路径)。
 * 上游:平台文件页面;下游:FileStorageService.platformPage。
 */
@Tag(name = "平台文件")
@RestController
@RequestMapping("/platform/file")
public class FilePlatformController {

    /** 文件服务 */
    private final FileStorageService fileStorageService;

    public FilePlatformController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PreAuthorize("hasAuthority('platform:file:list')")
    @Operation(summary = "文件分页(跨租户)")
    @GetMapping("/page")
    public R<PageResult<FileVO>> page(@Valid FileQuery query) {
        return R.ok(fileStorageService.platformPage(query));
    }
}
