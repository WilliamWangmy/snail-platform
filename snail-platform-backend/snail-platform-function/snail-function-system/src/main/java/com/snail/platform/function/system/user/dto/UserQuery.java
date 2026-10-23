package com.snail.platform.function.system.user.dto;

import com.snail.platform.framework.core.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 职责:用户分页查询契约。查询参数超过三个即收敛成对象,便于继续加筛选而不撑爆方法签名。
 * 上游:UserController 的 GET 查询参数绑定;下游:UserService.page(叠加数据权限窗口)。
 * 口径:username/nickname 模糊匹配,null/空白视为不限;orgId 精确匹配本级,不含下级(下级走数据权限档位或 subtree 接口);
 * 实现 framework-core PageQuery,分页对象由 PageAssembler 单点装配,本页字段仍就地维护。
 */
@Schema(description = "用户查询参数")
public record UserQuery(

        @Schema(description = "页码,从 1 起", defaultValue = "1")
        @Min(value = 1, message = "页码从 1 起") Integer pageNum,

        @Schema(description = "每页条数,1-200", defaultValue = "10")
        @Min(value = 1, message = "每页至少 1 条")
        @Max(value = 200, message = "每页至多 200 条") Integer pageSize,

        @Schema(description = "所属组织 ID,精确匹配")
        Long orgId,

        @Schema(description = "登录名,模糊匹配")
        String username,

        @Schema(description = "昵称,模糊匹配")
        String nickname,

        @Schema(description = "状态,0 正常 1 停用,不传不限")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1") Integer status) implements PageQuery {

    public UserQuery {
        // 包装类型接绑定:不传即 null,紧凑构造器落默认值,校验对 null 天然放行
        pageNum = pageNum == null ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : pageSize;
    }
}
