package com.snail.platform.function.system.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 职责:修改租户资料契约。tenant_code 不在其中——建后不可改是硬口径;status 可在此顺带改,
 * 但停用/启用的正路是独立端点(带留痕与幂等短路)。
 * 五系统开关与存储配额整份覆盖:开关不传按关闭落库(列 NOT NULL,留空即关闭);
 * 任一开关 1→0 会作废该租户全部存量令牌(OQ-1 默认口径),由服务端判定。
 * 上游:平台管理端表单;下游:TenantService.update。
 */
@Schema(description = "修改租户请求")
public record TenantUpdateRequest(

        @Schema(description = "租户名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入租户名称")
        @Size(max = 50, message = "租户名称过长")
        String name,

        @Schema(description = "联系人")
        @Size(max = 30, message = "联系人过长")
        String contactUser,

        @Schema(description = "联系电话")
        @Size(max = 20, message = "联系电话过长")
        String contactPhone,

        @Schema(description = "到期时间,null 为不限;改到过去由服务端拦下,原样回传现值不算改")
        LocalDateTime expireTime,

        @Schema(description = "账号数上限,null 为不限")
        @Positive(message = "账号数上限须为正数")
        Integer accountLimit,

        @Schema(description = "存储配额 MB,null 为不限;前端按 GB×1024 换算提交")
        @Positive(message = "存储配额须为正数")
        Long storageLimitMb,

        @Schema(description = "管理系统开通:0 未开通 1 已开通;不传按关闭落库(整份覆盖口径,列 NOT NULL)")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer adminEnabled,

        @Schema(description = "流程系统开通:0 未开通 1 已开通;不传按关闭落库(整份覆盖口径,列 NOT NULL)")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer workflowEnabled,

        @Schema(description = "移动端(app 与小程序)开通:0 未开通 1 已开通;不传按关闭落库(整份覆盖口径,列 NOT NULL)")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer mobileEnabled,

        @Schema(description = "数据大屏开通:0 未开通 1 已开通;不传按关闭落库(整份覆盖口径,列 NOT NULL)")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer dashboardEnabled,

        @Schema(description = "企业门户开通:0 未开通 1 已开通;不传按关闭落库(整份覆盖口径,列 NOT NULL)")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer portalEnabled,

        @Schema(description = "状态:0 正常 1 停用")
        @Min(value = 0, message = "状态取值 0/1")
        @Max(value = 1, message = "状态取值 0/1")
        Integer status,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark) {
}
