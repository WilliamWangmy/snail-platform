package com.snail.platform.function.system.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 职责:新建租户契约。租户资料 + 五系统开通开关 + 配额 + 首任管理员账号一并提交,服务端同事务复制 V4 种子模板;
 * adminPassword 可空,不传由服务端随机生成。
 * 上游:平台管理端表单;下游:TenantService.provision。
 * 口径:expireTime/accountLimit/storageLimitMb 为 null 表示不设限,0 不是不限;
 *      五开关不传按未开通(0)落库——开通语义是"平台管理员显式点开",缺省即关。
 */
@Schema(description = "新建租户请求")
public record TenantCreateRequest(

        @Schema(description = "租户标识,全局唯一,建后不可改", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入租户标识")
        @Size(min = 2, max = 64, message = "租户标识长度 2-64")
        @Pattern(regexp = "[a-z0-9](?:[a-z0-9._-]*[a-z0-9])?",
                message = "租户标识只允许小写字母、数字、点、下划线与连字符,首尾须为字母或数字")
        String tenantCode,

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

        @Schema(description = "到期时间,null 为不限;开通即送的场景只接受未来时间,过去时间建出来就是已到期")
        @Future(message = "到期时间必须晚于当前时间")
        LocalDateTime expireTime,

        @Schema(description = "账号数上限,null 为不限")
        @Positive(message = "账号数上限须为正数")
        Integer accountLimit,

        @Schema(description = "存储配额 MB,null 为不限;前端按 GB×1024 换算提交")
        @Positive(message = "存储配额须为正数")
        Long storageLimitMb,

        @Schema(description = "管理系统开通:0 未开通 1 已开通;未开通则登录管理系统被拒")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer adminEnabled,

        @Schema(description = "流程系统开通:0 未开通 1 已开通;未开通则登录流程系统被拒")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer workflowEnabled,

        @Schema(description = "移动端(app 与小程序)开通:0 未开通 1 已开通;未开通则 scene=app/miniapp 登录被拒")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer mobileEnabled,

        @Schema(description = "数据大屏开通:0 未开通 1 已开通;未开通则登录数据大屏被拒")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer dashboardEnabled,

        @Schema(description = "企业门户开通:0 未开通 1 已开通;门户立项前为配置先行")
        @Min(value = 0, message = "开通开关取值 0/1")
        @Max(value = 1, message = "开通开关取值 0/1")
        Integer portalEnabled,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注过长")
        String remark,

        @Schema(description = "首任管理员登录名,全局唯一", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入管理员登录名")
        @Size(min = 3, max = 30, message = "登录名长度 3-30")
        @Pattern(regexp = "[A-Za-z0-9_.@-]+", message = "登录名只允许字母、数字、下划线、点、@、连字符")
        String adminUsername,

        @Schema(description = "首任管理员初始口令,平台管理员自输;服务端按 PasswordPolicy 校验,不再代生成", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入管理员初始密码")
        @Size(min = 8, max = 20, message = "密码长度需 8-20 位")
        String adminPassword) {
}
