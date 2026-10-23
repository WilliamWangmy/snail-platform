package com.snail.platform.function.system.dict.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_dict_data 表 PO。字典的数据项,归属本租户的某个字典类型;dictValue 是业务落库值。
 * 上游:framework-db 的 TenantAuditedEntity(租户拦截器自动圈定本租户);下游:dict 子域的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_dict_data")
public class SysDictData extends TenantAuditedEntity {

    /** 字典数据 ID */
    @TableId(value = "dict_data_id", type = IdType.AUTO)
    private Long dictDataId;

    /** 所属字典类型 ID */
    private Long dictId;

    /** 字典标签,界面展示用 */
    private String dictLabel;

    /** 字典值,业务落库用;同一类型内唯一含已删记录,建后不可改 */
    private String dictValue;

    /** 同类型内显示顺序 */
    private Integer sort;

    /** 状态:0 正常 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
