/**
 * 流程实例：WorkflowInstanceController 镜像。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

export interface WorkflowInstanceVO {
  instanceId: string;
  definitionKey: string;
  definitionName?: string;
  businessKey?: string;
  startTime?: string;
  endTime?: string;
  suspended?: boolean;
}

export interface InstanceStartRequest {
  definitionId?: string;
  modelKey?: string;
  businessKey?: string;
  variables?: Record<string, string | number | boolean>;
}

export interface InstanceTraceNode {
  activityId: string;
  activityName?: string;
  startTime?: string;
  endTime?: string;
  assignee?: string;
}

/** 发起流程 */
export function startInstance(data: InstanceStartRequest): Promise<WorkflowInstanceVO> {
  return http<WorkflowInstanceVO>({ url: '/workflow/instance', method: 'POST', data });
}

/** 我发起的分页 */
export function pageStartedInstances(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<WorkflowInstanceVO>> {
  return http<PageResult<WorkflowInstanceVO>>({ url: '/workflow/instance/started', method: 'GET', params: query });
}

/** 流程实例详情 */
export function getInstance(instanceId: string): Promise<WorkflowInstanceVO> {
  return http<WorkflowInstanceVO>({ url: `/workflow/instance/${instanceId}`, method: 'GET' });
}

/** 流程轨迹（驱动流程图高亮） */
export function getInstanceTrace(instanceId: string): Promise<InstanceTraceNode[]> {
  return http<InstanceTraceNode[]>({ url: `/workflow/instance/${instanceId}/trace`, method: 'GET' });
}

/** 撤销在途流程 */
export function withdrawInstance(instanceId: string): Promise<void> {
  return http<void>({ url: `/workflow/instance/${instanceId}/withdraw`, method: 'POST' });
}
