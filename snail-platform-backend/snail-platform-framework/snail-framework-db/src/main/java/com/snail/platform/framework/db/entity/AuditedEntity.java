package com.snail.platform.framework.db.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:审计四件套 + 逻辑删除的公共基类,平台级表(不带租户列)直接继承它。
 * 日志表不继承——只增不改不删,给它假删除字段就是给篡改留门。
 * 上游:SnailAuditMetaObjectHandler 按这里的填充注解工作;下游:function 各模块业务 PO。
 */
@Getter
@Setter
public abstract class AuditedEntity {

    /** 创建人用户 ID,insert 自动填充 */
    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private Long createBy;

    /** 创建时间,insert 自动填充 */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 修改人用户 ID,update 自动填充 */
    @TableField(value = "update_by", fill = FieldFill.UPDATE)
    private Long updateBy;

    /** 修改时间,update 自动填充 */
    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除标记:0 正常 1 已删;查询由 @TableLogic 自动加条件 */
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
