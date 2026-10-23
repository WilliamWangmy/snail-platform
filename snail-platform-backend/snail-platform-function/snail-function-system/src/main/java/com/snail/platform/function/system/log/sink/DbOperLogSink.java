package com.snail.platform.function.system.log.sink;

import com.snail.platform.framework.log.OperLogRecord;
import com.snail.platform.framework.log.OperLogSink;
import com.snail.platform.function.system.log.converter.OperLogConverter;
import com.snail.platform.function.system.log.mapper.SysOperLogMapper;
import org.springframework.stereotype.Component;

/**
 * 职责:framework-log 留痕的唯一落库实现——OperLogRecord 经 MapStruct 映射为 sys_oper_log 行后插入。
 * 上游:OperLogAspect 的异步线程(无租户/操作人上下文,快照字段全部来自 record 本身);
 * 下游:OperLogConverter、SysOperLogMapper。sys_oper_log 在租户拦截器 ignore 清单,tenant_id 用切面取好的值直写。
 */
@Component
public class DbOperLogSink implements OperLogSink {

    /** 框架模型→PO 的编译期映射 */
    private final OperLogConverter converter;

    /** 操作日志表写 */
    private final SysOperLogMapper operLogMapper;

    public DbOperLogSink(OperLogConverter converter, SysOperLogMapper operLogMapper) {
        this.converter = converter;
        this.operLogMapper = operLogMapper;
    }

    @Override
    public void save(OperLogRecord record) {
        operLogMapper.insert(converter.toEntity(record));
    }
}
