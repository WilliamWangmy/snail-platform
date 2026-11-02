/**
 * 流程模型与定义：WorkflowModelController / WorkflowDefinitionController 镜像。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

export interface WorkflowModelVO {
  modelId: string;
  modelKey: string;
  modelName: string;
  category?: string;
  deployedDefinitionId?: string;
  version?: number;
  suspended?: boolean;
}

export interface WorkflowModelSaveRequest {
  modelKey: string;
  modelName: string;
  category?: string;
  bpmnXml?: string;
}

export interface WorkflowDefinitionVO {
  definitionId: string;
  definitionKey: string;
  definitionName?: string;
  version: number;
  deployed: boolean;
}

/** 模型分页 */
export function pageModels(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<WorkflowModelVO>> {
  return http<PageResult<WorkflowModelVO>>({ url: '/workflow/model/page', method: 'GET', params: query });
}

/** 模型详情（不含 XML） */
export function getModel(modelId: string): Promise<WorkflowModelVO> {
  return http<WorkflowModelVO>({ url: `/workflow/model/${modelId}`, method: 'GET' });
}

/** 新增模型（可直接给草稿，或从平台模板落地） */
export function createModel(data: WorkflowModelSaveRequest): Promise<WorkflowModelVO> {
  return http<WorkflowModelVO>({ url: '/workflow/model', method: 'POST', data });
}

/** 修改模型展示信息（流程标识不可改） */
export function updateModel(modelId: string, data: Partial<WorkflowModelSaveRequest>): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}`, method: 'PUT', data });
}

/** 删除模型（有在途实例时拒绝） */
export function deleteModel(modelId: string): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}`, method: 'DELETE' });
}

/** 取设计器草稿（BPMN XML） */
export function getModelDraft(modelId: string): Promise<string> {
  return http<string>({ url: `/workflow/model/${modelId}/draft`, method: 'GET' });
}

/** 保存设计器草稿（整份覆盖） */
export function saveModelDraft(modelId: string, bpmnXml: string): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/draft`, method: 'PUT', data: bpmnXml });
}

/** 发布模型（校验 + 部署 + 回写版本指针） */
export function publishModel(modelId: string): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/publish`, method: 'POST' });
}

/** 版本列表（当前指针标 deployed） */
export function listModelDefinitions(modelId: string): Promise<WorkflowDefinitionVO[]> {
  return http<WorkflowDefinitionVO[]>({ url: `/workflow/model/${modelId}/definitions`, method: 'GET' });
}

/** 停用流程（挂起当前定义，禁止新发起） */
export function suspendModel(modelId: string): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/suspend`, method: 'POST' });
}

/** 启用流程（激活当前定义，恢复可发起） */
export function activateModel(modelId: string): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/activate`, method: 'POST' });
}

/** 已发布版本的 BPMN 原文（只读回显） */
export function getDefinitionXml(definitionId: string): Promise<string> {
  return http<string>({ url: `/workflow/definition/${definitionId}/xml`, method: 'GET' });
}
