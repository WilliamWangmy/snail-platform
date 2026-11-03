/**
 * 流程实例：WorkflowInstanceController 镜像。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

export type WorkflowStatus = 'NONE' | 'RUNNING' | 'APPROVED' | 'REJECTED' | 'WITHDRAWN';

/** WorkflowInstanceVO 镜像 */
export interface WorkflowInstanceVO {
  instanceId: string;
  definitionKey: string;
  title?: string;
  businessKey?: string;
  status: WorkflowStatus;
  initiatorId?: number;
  startTime?: string;
  endTime?: string;
}

/** WorkflowStartRequest 镜像：直接发起（无业务单据） */
export interface InstanceStartRequest {
  definitionKey: string;
  businessType?: string;
  businessKey?: string;
  businessTitle?: string;
  variables?: Record<string, unknown>;
}

/** WorkflowApi.StartResult 镜像 */
export interface InstanceStartResult {
  instanceId: string;
  definitionId: string;
}

/** WorkflowTraceVO.Step 镜像 */
export interface WorkflowTraceStep {
  action: string;
  taskId?: string;
  taskName?: string;
  operatorId?: number;
  operatorName?: string;
  targetUserName?: string;
  comment?: string;
  time?: string;
}

/** WorkflowTraceVO.Waiting 镜像 */
export interface WorkflowTraceWaiting {
  taskId: string;
  taskName?: string;
  assigneeId?: number | null;
  assigneeName?: string | null;
}

/** WorkflowTraceVO 镜像 */
export interface WorkflowTraceVO {
  instance: WorkflowInstanceVO;
  steps: WorkflowTraceStep[];
  waiting: WorkflowTraceWaiting[];
}

/** 发起流程 */
export function startInstance(data: InstanceStartRequest): Promise<InstanceStartResult> {
  return http<InstanceStartResult>({ url: '/workflow/instance', method: 'POST', data });
}

/** 我发起的分页 */
export function pageStartedInstances(
  query: PageQueryParam & { definitionKey?: string }
): Promise<PageResult<WorkflowInstanceVO>> {
  return http<PageResult<WorkflowInstanceVO>>({ url: '/workflow/instance/started', method: 'GET', params: query });
}

/** 流程实例详情 */
export function getInstance(instanceId: string): Promise<WorkflowInstanceVO> {
  return http<WorkflowInstanceVO>({ url: `/workflow/instance/${instanceId}`, method: 'GET' });
}

/** 流程轨迹（步骤 + 在途节点） */
export function getInstanceTrace(instanceId: string): Promise<WorkflowTraceVO> {
  return http<WorkflowTraceVO>({ url: `/workflow/instance/${instanceId}/trace`, method: 'GET' });
}

/** 撤销在途流程 */
export function withdrawInstance(instanceId: string, reason: string): Promise<void> {
  return http<void>({ url: `/workflow/instance/${instanceId}/withdraw`, method: 'POST', data: { reason } });
}
