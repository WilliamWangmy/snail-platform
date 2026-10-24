package com.snail.platform.function.notify.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:平台公告分页查询契约。平台面跨租户检索,标题模糊,其余在服务端固定 biz_type=announcement。
 * 上游:公告管理页面;下游:NotifyService.announcements、PageAssembler。
 */
@Schema(description = "公告查询参数")
public record AnnouncementQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "标题,模糊匹配")
        String title) implements PageQuery {

    public AnnouncementQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
