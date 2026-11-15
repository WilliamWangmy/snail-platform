package com.snail.platform.function.monitor.arthas.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.monitor.arthas.ArthasConsoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:Arthas 状态入口——只读快照(tick 刷新),不做任何命令代理。
 * 上游:监控前端诊断页 Arthas tab;下游:ArthasConsoleService。
 * 口径:权限码 platform:monitor:arthas(§5.6);enabled=false 也走权限校验(仍 403 保护);
 * 凭据永不回吐。
 */
@Tag(name = "Arthas 在线诊断")
@Validated
@RestController
@RequestMapping("/monitor/arthas")
public class ArthasStatusController {

    /** Arthas 控制台服务 */
    private final ArthasConsoleService arthasConsoleService;

    public ArthasStatusController(ArthasConsoleService arthasConsoleService) {
        this.arthasConsoleService = arthasConsoleService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:arthas')")
    @Operation(summary = "Arthas tunnel 状态")
    @GetMapping("/status")
    public R<ArthasConsoleService.ArthasStatusVO> status() {
        return R.ok(arthasConsoleService.status());
    }
}
