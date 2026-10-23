package com.snail.platform.function.system.log.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 职责:登录日志分页/导出共用查询契约。参数超过三个即收敛成对象,两端口径天然一致。
 * 上游:LoginLogController 的 GET 查询参数绑定;下游:LoginLogService.page/export。
 * 口径:tenantId 仅平台侧生效;tenant_id=0 的"归属不明失败"行只有平台侧不筛租户时看得见;
 * 实现 framework-core PageQuery,分页对象由 PageAssembler 单点装配。
 */
@Schema(description = "登录日志查询参数")
public record LoginLogQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "租户 ID,仅平台侧可按租户筛,不传跨租户全量")
        Long tenantId,

        @Schema(description = "登录名,模糊匹配")
        String username,

        @Schema(description = "客户端 IP,模糊匹配")
        String ip,

        @Schema(description = "结果,1 成功 0 失败,不传不限")
        @Min(value = 0, message = "结果取值 0/1")
        @Max(value = 1, message = "结果取值 0/1") Integer status,

        @Schema(description = "登录时间起,ISO 局部日期时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,

        @Schema(description = "登录时间止,ISO 局部日期时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) implements PageQuery {

    public LoginLogQuery {
        // 包装类型接绑定:不传即 null,紧凑构造器落默认值,校验对 null 天然放行
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
