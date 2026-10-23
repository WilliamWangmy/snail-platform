package com.snail.platform.function.system.menu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_role_menu 表 PO。角色-菜单授权的关联行;整包替换时物理删改,无审计、无逻辑删除、无租户列。
 * 上游:MyBatis-Plus 直接映射;下游:role 子域的授权写入与权限解析。
 */
@Getter
@Setter
@TableName("sys_role_menu")
public class SysRoleMenu {

    /** 角色 ID;与 menuId 构成联合主键,MP 只认单主键,增删按双字段条件走 */
    @TableId(value = "role_id", type = IdType.INPUT)
    private Long roleId;

    /** 菜单 ID */
    private Long menuId;
}
