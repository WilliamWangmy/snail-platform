package com.snail.platform.function.storage.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:文件分页查询契约。文件名模糊匹配,业务域标签精确过滤。
 * 上游:文件管理页面与平台文件页面;下游:FileStorageService.page/platformPage、PageAssembler。
 */
@Schema(description = "文件查询参数")
public record FileQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "文件名,模糊匹配")
        String fileName,

        @Schema(description = "业务域标签,精确匹配")
        String bizType) implements PageQuery {

    public FileQuery {
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
