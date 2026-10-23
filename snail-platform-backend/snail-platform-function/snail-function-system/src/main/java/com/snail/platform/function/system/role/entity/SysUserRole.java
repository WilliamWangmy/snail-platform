package com.snail.platform.function.system.role.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_user_role 表 PO。用户-角色授权的关联行;关联即事实,无审计、无逻辑删除、无租户列(拦截器豁免)。
 * 上游:MyBatis-Plus 直接映射;下游:user/role 子域的授权写入。
 */
@Getter
@Setter
@TableName("sys_user_role")
public class SysUserRole {

    /** 用户 ID;与 roleId 构成联合主键,MP 只认单主键,增删按双字段条件走 */
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;

    /** 角色 ID */
    private Long roleId;
}
