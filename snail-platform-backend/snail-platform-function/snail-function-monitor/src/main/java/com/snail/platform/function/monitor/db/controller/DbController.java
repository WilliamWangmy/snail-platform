package com.snail.platform.function.monitor.db.controller;

import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.monitor.db.dto.DbSummaryVO;
import com.snail.platform.function.monitor.db.dto.SlowSqlQueryVO;
import com.snail.platform.function.monitor.db.service.DbMonitorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:数据库面板入口——池/慢 SQL/服务端三段汇总与慢 SQL 详查,只读。
 * 上游:监控前端总览页数据库面板;下游:DbMonitorService。
 * 口径:权限码 platform:monitor:db(§5.1.1);SQL 文本前端一律转义渲染。
 */
@Tag(name = "数据库监控")
@Validated
@RestController
@RequestMapping("/monitor/db")
public class DbController {

    /** 数据库面板服务 */
    private final DbMonitorService dbMonitorService;

    public DbController(DbMonitorService dbMonitorService) {
        this.dbMonitorService = dbMonitorService;
    }

    @PreAuthorize("hasAuthority('platform:monitor:db')")
    @Operation(summary = "面板汇总(池/SQL/服务端/慢 SQL Top5)")
    @GetMapping("/summary")
    public R<DbSummaryVO> summary() {
        return R.ok(dbMonitorService.summary());
    }

    @PreAuthorize("hasAuthority('platform:monitor:db')")
    @Operation(summary = "慢 SQL 详查(Top20 + 最近 50)")
    @GetMapping("/slow")
    public R<SlowSqlQueryVO> slow() {
        return R.ok(dbMonitorService.slow());
    }
}
