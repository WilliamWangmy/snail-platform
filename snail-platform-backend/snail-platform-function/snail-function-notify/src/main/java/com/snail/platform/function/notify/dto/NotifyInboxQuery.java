package com.snail.platform.function.notify.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:收件箱分页查询契约。私人数据口径——服务层强制本人谓词,这里只有筛选形态。
 * 上游:收件箱页面;下游:NotifyService.inbox、PageAssembler。
 */
@Schema(description = "收件箱查询参数")
public record NotifyInboxQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "已读状态:0 未读 1 已读,不传不限")
        @Min(value = 0, message = "已读状态取值 0/1")
        @Max(value = 1, message = "已读状态取值 0/1") Integer readStatus,

        @Schema(description = "业务域标签,精确匹配")
        String bizType) implements PageQuery {

    public NotifyInboxQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
