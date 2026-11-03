/**
 * 任务中心：WorkflowTaskController 镜像。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

/** WorkflowTodoVO 镜像 */
export interface WorkflowTodoVO {
  taskId: string;
  taskDefinitionKey: string;
  taskName: string;
  instanceId: string;
  definitionKey?: string;
  businessKey?: string;
  instanceName?: string;
  initiatorId?: number;
  initiatorName?: string;
  assigneeId?: number | null;
  assigneeName?: string | null;
  claimed: boolean;
  createTime?: string;
  dueDate?: string;
}

/** WorkflowDoneVO 镜像 */
export interface WorkflowDoneVO {
  taskId: string;
  taskDefinitionKey: string;
  taskName: string;
  instanceId: string;
  definitionKey?: string;
  businessKey?: string;
  instanceName?: string;
  initiatorId?: number;
  initiatorName?: string;
  createTime?: string;
  handledAt?: string;
}

/** 我的待办分页 */
export function pageTodoTasks(query: PageQueryParam): Promise<PageResult<WorkflowTodoVO>> {
  return http<PageResult<WorkflowTodoVO>>({ url: '/workflow/task/todo', method: 'GET', params: query });
}

/** 我的已办分页 */
export function pageDoneTasks(query: PageQueryParam): Promise<PageResult<WorkflowDoneVO>> {
  return http<PageResult<WorkflowDoneVO>>({ url: '/workflow/task/done', method: 'GET', params: query });
}

/** 签收任务：审批意见必填 */
export function claimTask(taskId: string, comment: string): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/claim`, method: 'POST', data: { comment } });
}

/** 审批通过：审批意见必填 */
export function approveTask(taskId: string, comment: string): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/approve`, method: 'POST', data: { comment } });
}

/** 审批驳回：审批意见必填 */
export function rejectTask(taskId: string, comment: string): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/reject`, method: 'POST', data: { comment } });
}

/** 转办：目标用户 + 可选缘由 */
export function transferTask(taskId: string, targetUserId: number, comment?: string): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/transfer`, method: 'POST', data: { targetUserId, comment } });
}
