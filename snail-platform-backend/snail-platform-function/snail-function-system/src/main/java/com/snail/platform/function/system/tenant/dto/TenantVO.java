package com.snail.platform.function.system.tenant.dto;

import com.snail.platform.function.system.tenant.entity.SysTenant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:租户出参对象。平台侧列表与详情的唯一投影,不含审计修改足迹。
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

        @Schema(description = "状态:0 正常 1 停用")
        Integer status,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间")
        LocalDateTime createTime) {

    public static TenantVO from(SysTenant tenant) {
        return new TenantVO(tenant.getTenantId(), tenant.getTenantCode(), tenant.getName(),
                tenant.getContactUser(), tenant.getContactPhone(), tenant.getExpireTime(),
                tenant.getAccountLimit(), tenant.getStatus(), tenant.getRemark(), tenant.getCreateTime());
    }
}
