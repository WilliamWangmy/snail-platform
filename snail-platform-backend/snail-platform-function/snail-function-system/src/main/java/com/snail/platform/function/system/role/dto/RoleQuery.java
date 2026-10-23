package com.snail.platform.function.system.role.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:角色分页查询契约。查询参数超过三个即收敛成对象,page 口径唯一。
 * 上游:RoleController 的 GET 查询参数绑定;下游:RoleService.page。
 * 口径:keyword 同时模糊匹配角色名称与标识,任一命中即返回;
 * 实现 framework-core PageQuery,分页对象由 PageAssembler 单点装配。
 */
@Schema(description = "角色查询参数")
public record RoleQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "10")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "关键字,模糊匹配角色名称或标识")
        String keyword,

        @Schema(description = "状态,0 正常 1 停用,不传不限")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1") Integer status) implements PageQuery {

    public RoleQuery {
        // 包装类型接绑定:不传即 null,紧凑构造器落默认值,校验对 null 天然放行
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
