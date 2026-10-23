package com.snail.platform.framework.db.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:租户托管业务表基类——审计四件套 + 逻辑删除之外再加 tenant_id。
 * 该列由租户拦截器在 SQL 层注入,业务代码不允许读写它;字段存在只为让查询结果能映射回来。
 * 上游:AuditedEntity;下游:sys_user、sys_org、sys_role 等租户级 PO。
 */
@Getter
@Setter
public abstract class TenantAuditedEntity extends AuditedEntity {

    /** 所属租户 ID,拦截器注入与回读用,业务侧不手写 */
    @TableField("tenant_id")
    private Long tenantId;
}
