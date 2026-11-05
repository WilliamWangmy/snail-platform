package com.snail.platform.function.system.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_user_identity 表 PO。第三方身份与平台账号的绑定关系,一行=厂商身份x平台账号;
 * 不继承审计基类(绑定行无假删除,错绑物理删)。租户拦截器豁免表:登录按 (source,open_id)
 * 跨租户定位,tenantId 是绑定时刻的展示快照,由写入方显式赋值,不做过滤列。
 * 上游:auth 登录链写入绑定与最近登录;下游:用户详情只读展示。
 */
@Getter
@Setter
@TableName("sys_user_identity")
public class SysUserIdentity {

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 平台用户 ID,sys_user.user_id */
    private Long userId;

    /** 绑定时刻的用户租户快照,仅展示口径 */
    private Long tenantId;

    /** 来源编码:dingtalk/feishu/wecom */
    private String source;

    /** 厂商内稳定标识:钉钉 unionId/飞书 openId/企微 userid */
    private String openId;

    /** 厂商跨应用标识:钉钉与 openId 同值,飞书预留 */
    private String unionId;

    /** 最近一次厂商昵称快照 */
    private String nickname;

    /** 最近一次经此身份登录时间,毫秒精度 */
    private LocalDateTime lastLoginTime;

    /** 绑定时间,毫秒精度 */
    private LocalDateTime createTime;

    /** 最近更新时间,毫秒精度 */
    private LocalDateTime updateTime;
}
