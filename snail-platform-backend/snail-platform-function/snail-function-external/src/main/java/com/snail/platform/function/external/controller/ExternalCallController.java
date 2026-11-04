package com.snail.platform.function.external.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.external.dto.ExternalCallLogQuery;
import com.snail.platform.function.external.dto.ExternalCallLogVO;
import com.snail.platform.function.external.service.ExternalCallLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:第三方调用流水入口——平台面的只读检索;流水是账本,不提供删除/导出(后置)。
 * 上游:第三方调用页面;下游:ExternalCallLogService。
 */
@Tag(name = "第三方调用")
@RestController
@RequestMapping("/platform/external")
public class ExternalCallController {

    /** 流水查询服务 */
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
}
