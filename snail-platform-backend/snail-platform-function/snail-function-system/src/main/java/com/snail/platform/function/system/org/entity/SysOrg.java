package com.snail.platform.function.system.org.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_org 表 PO。每租户一棵树,部门与单位同构;层级由 ancestors 推导,不另存。
 * 上游:framework-db 的 TenantAuditedEntity;下游:org 子域的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_org")
public class SysOrg extends TenantAuditedEntity {

    /** 组织 ID */
    @TableId(value = "org_id", type = IdType.AUTO)
    private Long orgId;

    /** 父组织 ID,根节点记 0 */
    private Long parentId;

    /** 组织名称 */
    private String name;

    /** 祖级路径,逗号分隔如 0,1,5;子树查询与数据权限的地基,写入路径不许绕过 */
    private String ancestors;

    /** 同级显示顺序 */
    private Integer sort;

    /** 状态:0 正常 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
