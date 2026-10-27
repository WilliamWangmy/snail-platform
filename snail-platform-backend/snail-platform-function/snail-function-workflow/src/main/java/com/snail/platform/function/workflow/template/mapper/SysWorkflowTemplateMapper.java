package com.snail.platform.function.workflow.template.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.snail.platform.function.workflow.template.entity.SysWorkflowTemplate;

/**
 * 职责:sys_workflow_template 数据访问。全局表(在租户 ignore 清单内),读写都不需要 bypass 窄窗口。
 * 上游:MybatisPlusConfig 的 @MapperScan;下游:template 子域 service 与 model 子域的"从模板建模型"。
 */
public interface SysWorkflowTemplateMapper extends BaseMapper<SysWorkflowTemplate> {
}
