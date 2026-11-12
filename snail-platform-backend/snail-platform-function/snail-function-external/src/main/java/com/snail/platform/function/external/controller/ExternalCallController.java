package com.snail.platform.function.external.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.external.dto.ExternalCallLogCleanRequest;
import com.snail.platform.function.external.dto.ExternalCallLogQuery;
import com.snail.platform.function.external.dto.ExternalCallLogVO;
import com.snail.platform.function.external.service.ExternalCallLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:第三方调用流水入口——平台面的检索与历史清除;流水是账本,不提供单条删除/导出(后置),
 * 唯一写端点是按保留期清除历史。上游:第三方调用页面;下游:ExternalCallLogService。
 */
@Tag(name = "第三方调用")
@RestController
@RequestMapping("/platform/external")
public class ExternalCallController {

    /** 流水服务 */
    private final ExternalCallLogService callLogService;

    public ExternalCallController(ExternalCallLogService callLogService) {
        this.callLogService = callLogService;
    }

    @PreAuthorize("hasAuthority('platform:external:log')")
    @Operation(summary = "第三方调用流水分页")
    @GetMapping("/call-log")
    public R<PageResult<ExternalCallLogVO>> callLog(@Valid ExternalCallLogQuery query) {
        return R.ok(callLogService.page(query));
    }

    @PreAuthorize("hasAuthority('platform:external:clean')")
    @Operation(summary = "清除历史调用流水(1m/6m/1y 三档保留期,不可逆)")
    @PostMapping("/call-log/clean")
    public R<Long> clean(@Valid @RequestBody ExternalCallLogCleanRequest request) {
        return R.ok(callLogService.clean(request));
    }
}
