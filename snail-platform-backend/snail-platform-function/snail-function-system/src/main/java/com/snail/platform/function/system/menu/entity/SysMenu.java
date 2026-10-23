package com.snail.platform.function.system.menu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.AuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_menu 表 PO。全局唯一一份菜单目录,平台维护;租户只经 sys_role_menu 授权,不持有副本。
 * 上游:framework-db 的 AuditedEntity;下游:menu 子域 Mapper、role 子域的权限解析与授权服务。
 */
@Getter
@Setter
@TableName("sys_menu")
public class SysMenu extends AuditedEntity {

    /** 菜单 ID */
    @TableId(value = "menu_id", type = IdType.AUTO)
    private Long menuId;

    /** 父菜单 ID,根节点记 0 */
    private Long parentId;

    /** 菜单名称,界面展示用 */
    private String menuName;

    /** 类型:1 目录 2 菜单 3 按钮 */
    private Integer menuType;

    /** 展示端:1 站内 2 数据大屏 3 外链 */
    private Integer target;

    /** 归属面:1 平台 2 租户 */
    private Integer scope;

    /** 路由或完整外链地址 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 权限码,目录与外链为空;全局唯一含已删记录 */
    private String permCode;

    /** 图标名 */
    private String icon;

    /** 同级显示顺序 */
    private Integer sort;

    /** 显示:0 显示 1 隐藏 */
    private Integer visible;

    /** 状态:0 正常 1 停用;停用后节点与子树退出菜单树和权限解析 */
    private Integer status;

    /** 内置节点:0 否 1 是;内置不可删、不可停用 */
    private Integer isSystem;

    /** 备注 */
    private String remark;
}
