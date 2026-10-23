package com.snail.platform.framework.log;

/**
 * 职责:操作日志的落库出口接口——framework-log 只定义"往哪交",不关心"怎么存"。
 * 实现方在 function 侧(sys_oper_log 的 Mapper  insert);一个实现都没有时切面静默丢弃并告警,
 * 不阻断业务——审计缺一条是事故,业务因审计写不进而宕机是更大的事故。
 * 上游:OperLogAspect(异步线程调用);下游:function-system 的实现 Bean。
 */
public interface OperLogSink {

    /** 落一条操作日志;实现方异常由切面捕获记应用日志,不外抛 */
    void save(OperLogRecord record);
}
