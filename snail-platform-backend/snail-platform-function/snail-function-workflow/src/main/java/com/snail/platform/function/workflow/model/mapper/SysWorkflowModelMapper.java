package com.snail.platform.function.workflow.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.workflow.model.entity.SysWorkflowModel;

/**
 * 职责:sys_workflow_model 数据访问。租户托管表,租户拦截器生效,服务层不写 tenant_id。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:model 子域 service。
 */
public interface SysWorkflowModelMapper extends BaseMapper<SysWorkflowModel> {
}
