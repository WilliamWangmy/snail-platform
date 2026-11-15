package com.snail.platform.function.monitor.diag.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.monitor.diag.dto.ReportQuery;
import com.snail.platform.function.monitor.diag.dto.ReportVO;
import com.snail.platform.function.monitor.diag.dto.ThreadSnapshotVO;
import com.snail.platform.function.monitor.diag.service.DiagnosticService;
import com.snail.platform.function.monitor.diag.service.EnvSnapshotService;
import com.snail.platform.function.monitor.diag.service.ThreadSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:诊断入口——run/报告(diag 码)、线程快照与下载(threads 码)、环境快照(env 码)五端点。
 * 上游:监控前端诊断页;下游:DiagnosticService/ThreadSnapshotService/EnvSnapshotService。
 * 口径:权限码三层分离(§5.6);下载用 Content-Disposition;run 写路径留痕。
 */
@Tag(name = "监控诊断")
@Validated
@RestController
@RequestMapping("/monitor/diag")
public class DiagController {

    /** 诊断报告服务 */
    private final DiagnosticService diagnosticService;

    /** 线程快照服务 */
    private final ThreadSnapshotService threadSnapshotService;

    /** 环境快照服务 */
    private final EnvSnapshotService envSnapshotService;

    public DiagController(DiagnosticService diagnosticService, ThreadSnapshotService threadSnapshotService,
                          EnvSnapshotService envSnapshotService) {
        this.diagnosticService = diagnosticService;
        this.threadSnapshotService = threadSnapshotService;
        this.envSnapshotService = envSnapshotService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:diag')")
    @Operation(summary = "一键体检(同步 12 项,限频 10s)")
    @OperLog(module = "监控中心", businessType = BusinessType.OTHER)
    @PostMapping("/run")
    public R<ReportVO> run() {
        return R.ok(diagnosticService.run());
    }

    @PreAuthorize("hasAuthority('platform:monitor:diag')")
    @Operation(summary = "报告分页")
    @GetMapping("/report/page")
    public R<PageResult<ReportVO>> page(@Valid ReportQuery query) {
        return R.ok(diagnosticService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:monitor:diag')")
    @Operation(summary = "报告详情(items 结构化回读)")
    @GetMapping("/report/{reportId}")
    public R<ReportVO> detail(@PathVariable Long reportId) {
        return R.ok(diagnosticService.detail(reportId));
    }

    @PreAuthorize("hasAuthority('platform:monitor:threads')")
    @Operation(summary = "线程快照(栈顶 8 行 + 死锁 ID)")
    @GetMapping("/threads")
    public R<ThreadSnapshotVO> threads() {
        return R.ok(threadSnapshotService.list());
    }

    @PreAuthorize("hasAuthority('platform:monitor:threads')")
    @Operation(summary = "线程全量 dump 下载(text/plain)")
    @GetMapping("/threads/download")
    public ResponseEntity<byte[]> threadsDownload() {
        byte[] body = threadSnapshotService.download();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("snail-threads-" + System.currentTimeMillis() + ".txt", StandardCharsets.UTF_8)
                .build());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @PreAuthorize("hasAuthority('platform:monitor:env')")
    @Operation(summary = "环境快照(敏感键掩码)")
    @GetMapping("/env")
    public R<Map<String, Map<String, String>>> env() {
        return R.ok(envSnapshotService.snapshot());
    }
}
