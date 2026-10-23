package com.snail.platform.framework.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 职责:操作日志打标注解——标在 Controller 写操作方法上,模块名与业务类型由打标方声明,
 * 其余字段(IP、入参、结果、耗时、traceId、操作人快照)全部由 OperLogAspect 自动采集。
 * 上游:各业务 Controller;下游:OperLogAspect。
 * 口径:写操作必标;查询类接口不标(整棵/分页的读留痕靠访问日志,不进审计表)。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {

    /** 功能模块标题,如"用户管理"——日志列表按它筛选 */
    String module();

    /** 业务类型 */
    BusinessType businessType() default BusinessType.OTHER;
}
