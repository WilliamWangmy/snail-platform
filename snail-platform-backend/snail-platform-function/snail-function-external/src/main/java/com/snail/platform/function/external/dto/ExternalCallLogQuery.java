package com.snail.platform.function.external.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 职责:第三方调用流水分页查询契约。平台面只读检索,按厂商/调用方/终态/时间范围筛。
 * 上游:第三方调用页面;下游:ExternalCallLogService、PageAssembler。
 */
@Schema(description = "第三方调用流水查询参数")
public record ExternalCallLogQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "20")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "厂商标识,精确匹配:smtp-mail/aliyun-sms/amap")
        String vendor,

        @Schema(description = "调用方标识,精确匹配:notify/auth/system")
        String caller,

        @Schema(description = "终态,0 成功 1 失败,不传不限")
        @Min(value = 0, message = "终态取值 0/1")
        @Max(value = 1, message = "终态取值 0/1") Integer status,

        @Schema(description = "调用时间起,ISO 局部日期时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime beginTime,

        @Schema(description = "调用时间止,ISO 局部日期时间")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) implements PageQuery {

    public ExternalCallLogQuery {
        // 包装类型接绑定:不传即 null,紧凑构造器落默认值,校验对 null 天然放行
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 20 : pageSize;
    }
}
