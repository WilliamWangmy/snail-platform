package com.snail.platform.function.system.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 职责:用户批量导入结果契约。口径是"全量校验、任一行不合法即整批不入库"——
 * 失败时 successCount 恒为 0 且 failures 逐行给出行号与原因,前端整表回显让管理员改完再传;
 * 全部合法时一次事务落库,successCount 为导入行数、failures 为空。
 * 上游:UserController 导入入口;下游:用户管理页面导入弹窗。
 */
@Schema(description = "用户批量导入结果")
public record UserImportVO(

        @Schema(description = "成功导入数;任一行不合法即整批不入库,恒为 0")
        int successCount,

        @Schema(description = "失败行清单,整批未入库时逐行回报原因")
        List<Failure> failures) {

    /**
     * 单行失败回执:rowNum 是数据行的 Excel 行号(表头为第 1 行,首条数据从 2 起),
     * username 尽力回填便于定位,reason 用中文口径直接可展示。
     */
    @Schema(description = "导入失败行")
    public record Failure(

            @Schema(description = "Excel 数据行号,表头之后首条数据为 2")
            int rowNum,

            @Schema(description = "该行登录名,无法解析时为空串")
            String username,

            @Schema(description = "拒绝原因,可直接展示")
            String reason) {
    }
}
