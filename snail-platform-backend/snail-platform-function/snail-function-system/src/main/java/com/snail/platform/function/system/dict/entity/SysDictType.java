package com.snail.platform.function.system.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_dict_type 表 PO。字典的类型面,租户各自维护、无平台内置;dictType 是程序取值坐标。
 * 上游:framework-db 的 TenantAuditedEntity(租户拦截器自动圈定本租户);下游:dict 子域的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_dict_type")
public class SysDictType extends TenantAuditedEntity {

    /** 字典类型 ID */
    @TableId(value = "dict_id", type = IdType.AUTO)
    private Long dictId;

    /** 字典名称,界面展示用 */
    private String dictName;

    /** 字典类型标识,程序按此取值;租户内唯一含已删记录,建后不可改 */
    private String dictType;

    /** 状态:0 正常 1 停用;停用后该类型下数据不可取 */
    private Integer status;

    /** 备注 */
    private String remark;
}
