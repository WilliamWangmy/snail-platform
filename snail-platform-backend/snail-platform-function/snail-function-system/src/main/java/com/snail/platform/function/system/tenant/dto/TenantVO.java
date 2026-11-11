package com.snail.platform.function.system.tenant.dto;

import com.snail.platform.function.system.tenant.entity.SysTenant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:租户出参对象。平台侧列表与详情的唯一投影,不含审计修改足迹。
 * 五系统开关与存储配额随行:列表的功能开通 tag/存储配额(GB)两列与详情接口共用本投影。
 * 上游:TenantService 组装;下游:平台管理端。
 */
@Schema(description = "租户信息")
public record TenantVO(

        @Schema(description = "租户ID")
        Long tenantId,

        @Schema(description = "租户标识")
        String tenantCode,

        @Schema(description = "租户名称")
        String name,

        @Schema(description = "联系人")
        String contactUser,

        @Schema(description = "联系电话")
        String contactPhone,

        @Schema(description = "到期时间,null 为不限")
        LocalDateTime expireTime,

        @Schema(description = "账号数上限,null 为不限")
        Integer accountLimit,

        @Schema(description = "存储配额 MB,null 为不限")
        Long storageLimitMb,

        @Schema(description = "管理系统开通:0 未开通 1 已开通")
        Integer adminEnabled,

        @Schema(description = "流程系统开通:0 未开通 1 已开通")
        Integer workflowEnabled,

        @Schema(description = "移动端(app 与小程序)开通:0 未开通 1 已开通")
        Integer mobileEnabled,

        @Schema(description = "数据大屏开通:0 未开通 1 已开通")
        Integer dashboardEnabled,

        @Schema(description = "企业门户开通:0 未开通 1 已开通")
        Integer portalEnabled,

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间")
        LocalDateTime createTime) {

    public static TenantVO from(SysTenant tenant) {
        return new TenantVO(tenant.getTenantId(), tenant.getTenantCode(), tenant.getName(),
                tenant.getContactUser(), tenant.getContactPhone(), tenant.getExpireTime(),
                tenant.getAccountLimit(), tenant.getStorageLimitMb(),
                tenant.getAdminEnabled(), tenant.getWorkflowEnabled(), tenant.getMobileEnabled(),
                tenant.getDashboardEnabled(), tenant.getPortalEnabled(),
                tenant.getStatus(), tenant.getRemark(), tenant.getCreateTime());
    }
}
