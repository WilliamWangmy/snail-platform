package com.snail.platform.function.system.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_login_log 表 PO。登录成败皆记、只增;不继承审计基类(日志无修改无假删除)。
 * 上游:认证链的留痕写入;下游:log 子域查询。租户拦截器豁免表,tenantId 由写入方显式赋值。
 */
@Getter
@Setter
@TableName("sys_login_log")
public class SysLoginLog {

    /** 日志 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属租户;0=登录链还没定位到用户就失败了(验证码先行、锁定拦截、账号不存在),不等于"用户不存在" */
    private Long tenantId;

    /** 登录名快照:失败时用户可能不存在,删除后日志仍要可读 */
    private String username;

    /** 用户 ID,登录成功时回填 */
    private Long userId;

    /** 客户端 IP */
    private String ip;

    /** 浏览器 */
    private String browser;

    /** 操作系统 */
    private String os;

    /** 结果:1 成功 0 失败 */
    private Integer status;

    /** 失败原因 */
    private String msg;

    /** 登录时间,毫秒精度 */
    private LocalDateTime loginTime;
}
