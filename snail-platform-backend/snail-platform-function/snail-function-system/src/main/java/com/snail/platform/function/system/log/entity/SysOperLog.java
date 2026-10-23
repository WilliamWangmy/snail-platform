package com.snail.platform.function.system.log.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_oper_log 表 PO。操作审计,由 framework-log 切面异步落库、只增;不继承审计基类。
 * 上游:操作日志切面;下游:log 子域查询。租户拦截器豁免表,tenantId 由切面在请求线程取好后带入。
 */
@Getter
@Setter
@TableName("sys_oper_log")
public class SysOperLog {

    /** 日志 ID */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 归属租户 */
    private Long tenantId;

    /** 链路 ID,与应用日志互查 */
    private String traceId;

    /** 功能模块标题 */
    private String module;

    /** 业务类型:1 新增 2 修改 3 删除 4 导出 5 导入 9 其他 */
    private Integer businessType;

    /** Java 方法,类名.方法名 */
    private String method;

    /** HTTP 方法 */
    private String requestMethod;

    /** 操作人登录名快照 */
    private String operator;

    /** 操作人组织 ID,按部门筛选用 */
    private Long orgId;

    /** 客户端 IP */
    private String ip;

    /** 请求地址 */
    private String url;

    /** 请求入参,落库前已脱敏 */
    private String param;

    /** 响应出参 JSON */
    private String result;

    /** 结果:1 成功 0 失败 */
    private Integer status;

    /** 异常信息,超长截断 */
    private String errorMsg;

    /** 耗时(毫秒) */
    private Long costTime;

    /** 操作时间,毫秒精度 */
    private LocalDateTime operTime;
}
