package com.snail.platform.function.system.user.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:在线用户查询契约。数据源是 Redis 内存态,分页在服务层内存裁剪。
 * 上游:在线用户页面;下游:OnlineSessionService.page。
 */
@Schema(description = "在线用户查询参数")
public record OnlineQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "登录名/显示名,模糊匹配")
        String keyword) implements PageQuery {

    public OnlineQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
