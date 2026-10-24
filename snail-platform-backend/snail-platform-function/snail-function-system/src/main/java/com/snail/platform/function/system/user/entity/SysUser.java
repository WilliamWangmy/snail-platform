package com.snail.platform.function.system.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_user 表 PO。认证主体与档案,不含登录足迹(那是登录日志的投影)。
 * 上游:framework-db 的 TenantAuditedEntity;下游:user 子域的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser extends TenantAuditedEntity {

    /** 用户 ID */
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    /** 登录名,全表唯一;登录自动定位所属租户 */
    private String username;

    /** 显示名 */
    private String nickname;

    /** BCrypt 密文,任何出参对象禁止携带 */
    private String password;

    /** 令牌版本:改密/重置/停用/强制下线自增,进 JWT ver 声明实现吊销 */
    private Long tokenVersion;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 所属组织 ID;一用户一主组织 */
    private Long orgId;

    /** 状态:0 正常 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
