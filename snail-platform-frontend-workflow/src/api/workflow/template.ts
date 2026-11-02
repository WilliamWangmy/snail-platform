/**
 * 流程模板：WorkflowTemplateController 镜像（建模型用，只读消费）。
 */
import { http } from '@/utils/request';

/** WorkflowTemplateOptionVO 镜像 */
export interface WorkflowTemplateOptionVO {
  templateId: number;
  templateKey: string;
  name: string;
  category?: string;
  remark?: string;
}

/** 上架模板列表（建模型用） */
export function listTemplateOptions(): Promise<WorkflowTemplateOptionVO[]> {
  return http<WorkflowTemplateOptionVO[]>({ url: '/workflow/template/options', method: 'GET' });
}

/** 模板原文（建模型前预览） */
export function getTemplateXml(templateId: number): Promise<{ xml: string }> {
  return http<{ xml: string }>({ url: `/workflow/template/${templateId}/xml`, method: 'GET' });
}
