package com.snail.platform.function.workflow.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.workflow.task.entity.SysWorkflowTaskRecord;

/**
 * 职责:审批台账行写入。表只增,所以这里只用 insert;查询按实例或按单号在 service 里用条件构造器走。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:WorkflowTaskRecordService。
 */
public interface SysWorkflowTaskRecordMapper extends BaseMapper<SysWorkflowTaskRecord> {
}
