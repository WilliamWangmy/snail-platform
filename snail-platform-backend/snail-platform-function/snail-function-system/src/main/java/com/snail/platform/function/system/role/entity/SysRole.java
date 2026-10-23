package com.snail.platform.function.system.role.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_role 表 PO。权限包的身份面;数据权限只按 dataScope 枚举,不配组织清单表。
 * 上游:framework-db 的 TenantAuditedEntity;下游:role 子域的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_role")
public class SysRole extends TenantAuditedEntity {

    /** 角色 ID */
    @TableId(value = "role_id", type = IdType.AUTO)
    private Long roleId;

    /** 角色名称 */
    private String roleName;

    /** 角色标识,程序判权用(如 admin);租户内唯一,建后不可改 */
    private String roleCode;

    /** 数据权限:1 全部 2 本组织 3 本组织及以下 4 本人;多角色取谓词并集 */
    private Integer dataScope;

    /** 内置角色:0 否 1 是;内置不可删、不可改 roleCode */
    private Integer isSystem;

    /** 显示顺序 */
    private Integer sort;

    /** 状态:0 正常 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
