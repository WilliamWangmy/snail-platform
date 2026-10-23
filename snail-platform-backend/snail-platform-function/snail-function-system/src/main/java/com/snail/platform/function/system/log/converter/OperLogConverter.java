package com.snail.platform.function.system.log.converter;

import com.snail.platform.framework.log.OperLogRecord;
import com.snail.platform.function.system.log.entity.SysOperLog;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 职责:OperLogRecord → sys_oper_log PO 的编译期映射——18 字段 1:1 拷贝正是 MapStruct 的用武之地,
 * unmappedTargetPolicy=ERROR 让"新增列忘了落值"在编译期爆掉,而不是运行期静默写 null。
 * 上游:DbOperLogSink;下游:MapStruct 生成的 Spring Bean 实现。
 * 口径:映射三条边里只占"框架模型→PO"这一条;record→View 的展示投影走各自 of() 全参构造,字段即白名单。
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OperLogConverter {

    /** id 是自增主键,不在留痕模型里——显式 ignore 就是这条边字段差集的唯一合法表达 */
    @Mapping(target = "id", ignore = true)
    SysOperLog toEntity(OperLogRecord record);
}
