package com.snail.platform.function.system.tenant.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.AuditedEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_tenant 表 PO。平台级表,自身不带租户判别列,在租户拦截器豁免清单内。
 * 上游:framework-db 的 AuditedEntity;下游:tenant 子域的 service 与 mapper。
 */
@Getter
@Setter
@TableName("sys_tenant")
public class SysTenant extends AuditedEntity {

    /** 租户 ID,跨租户引用的代键 */
    @TableId(value = "tenant_id", type = IdType.AUTO)
    private Long tenantId;

    /** 租户标识,平台侧业务坐标,建后不可改,全局唯一 */
    private String tenantCode;

    /** 租户名称 */
    private String name;

    /** 联系人 */
    private String contactUser;

    /** 联系电话 */
    private String contactPhone;

    /** 到期时间,null 为不限 */
    private LocalDateTime expireTime;

    /** 账号数上限,null 为不限 */
    private Integer accountLimit;

    /** 存储配额 MB,null 为不限 */
    private Long storageLimitMb;

    /** 管理系统开通:0 未开通 1 已开通;未开通则 scene=admin 登录被拒 */
    private Integer adminEnabled;

    /** 流程系统开通:0 未开通 1 已开通;未开通则 scene=workflow 登录被拒 */
    private Integer workflowEnabled;

    /** 移动端(app 与小程序)开通:0 未开通 1 已开通;未开通则 scene=app/miniapp 登录被拒 */
    private Integer mobileEnabled;

    /** 数据大屏开通:0 未开通 1 已开通;未开通则 scene=dashboard 登录被拒 */
    private Integer dashboardEnabled;

    /** 企业门户开通:0 未开通 1 已开通;门户立项前为配置先行 */
    private Integer portalEnabled;

    /** 状态:0 正常 1 停用 */
    private Integer status;

    /** 备注 */
    private String remark;
}
