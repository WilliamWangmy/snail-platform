/**
 * 流程模型与定义：WorkflowModelController / WorkflowDefinitionController 镜像。
 * DTO 字段与后端 snail-function-workflow model/dto 下同名类一一对应。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

/** WorkflowModelVO 镜像 */
export interface WorkflowModelVO {
  modelId: number;
  modelKey: string;
  name: string;
  category?: string;
  remark?: string;
  status: number;
  templateId?: number;
  hasDraft: boolean;
  processDefinitionId?: string;
  deployedVersion?: number;
  publishTime?: string;
  createTime?: string;
  updateTime?: string;
}

/** WorkflowModelCreateRequest 镜像：draftXml 与 templateId 二选一 */
export interface WorkflowModelCreateRequest {
  modelKey: string;
  name: string;
  category?: string;
  remark?: string;
  templateId?: number;
  draftXml?: string;
}

/** WorkflowModelUpdateRequest 镜像：流程标识不可改 */
export interface WorkflowModelUpdateRequest {
  name: string;
  category?: string;
  remark?: string;
}

/** WorkflowDraftVO 镜像（GET draft 出参） */
export interface WorkflowDraftVO {
  modelId: number;
  modelKey: string;
  name: string;
  status: number;
  xml: string;
}

/** WorkflowDefinitionVO 镜像 */
export interface WorkflowDefinitionVO {
  definitionId: string;
  definitionKey: string;
  definitionName?: string;
  version: number;
  deploymentId?: string;
  suspended: boolean;
  deployed: boolean;
}

/** 模型分页 */
export function pageModels(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<WorkflowModelVO>> {
  return http<PageResult<WorkflowModelVO>>({ url: '/workflow/model/page', method: 'GET', params: query });
}

/** 模型详情（不含 XML） */
export function getModel(modelId: number): Promise<WorkflowModelVO> {
  return http<WorkflowModelVO>({ url: `/workflow/model/${modelId}`, method: 'GET' });
}

/** 新增模型（可直接给草稿，或从平台模板落地），返回新模型 ID */
export function createModel(data: WorkflowModelCreateRequest): Promise<number> {
  return http<number>({ url: '/workflow/model', method: 'POST', data });
}

/** 修改模型展示信息（流程标识不可改） */
export function updateModel(modelId: number, data: WorkflowModelUpdateRequest): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}`, method: 'PUT', data });
}

/** 删除模型（有在途实例时拒绝） */
export function deleteModel(modelId: number): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}`, method: 'DELETE' });
}

/** 取设计器草稿 */
export function getModelDraft(modelId: number): Promise<WorkflowDraftVO> {
  return http<WorkflowDraftVO>({ url: `/workflow/model/${modelId}/draft`, method: 'GET' });
}

/** 保存设计器草稿（整份覆盖） */
export function saveModelDraft(modelId: number, xml: string): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/draft`, method: 'PUT', data: { xml } });
}

/** 发布模型（校验 + 部署 + 回写版本指针） */
export function publishModel(modelId: number): Promise<WorkflowDefinitionVO> {
  return http<WorkflowDefinitionVO>({ url: `/workflow/model/${modelId}/publish`, method: 'POST' });
}

/** 版本列表（当前指针标 deployed） */
export function listModelDefinitions(modelId: number): Promise<WorkflowDefinitionVO[]> {
  return http<WorkflowDefinitionVO[]>({ url: `/workflow/model/${modelId}/definitions`, method: 'GET' });
}

/** 停用流程（挂起当前定义，禁止新发起） */
export function suspendModel(modelId: number): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/suspend`, method: 'POST' });
}

/** 启用流程（激活当前定义，恢复可发起） */
export function activateModel(modelId: number): Promise<void> {
  return http<void>({ url: `/workflow/model/${modelId}/activate`, method: 'POST' });
}

/** 已发布版本的 BPMN 原文（只读回显） */
export function getDefinitionXml(definitionId: string): Promise<{ xml: string }> {
  return http<{ xml: string }>({ url: `/workflow/definition/${definitionId}/xml`, method: 'GET' });
}
