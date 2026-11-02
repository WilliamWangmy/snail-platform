/**
 * 任务中心：WorkflowTaskController 镜像。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

export interface WorkflowTaskVO {
  taskId: string;
  taskName: string;
  instanceId: string;
  definitionKey?: string;
  assignee?: string;
  createTime?: string;
  claimed?: boolean;
}

/** 我的待办分页 */
export function pageTodoTasks(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<WorkflowTaskVO>> {
  return http<PageResult<WorkflowTaskVO>>({ url: '/workflow/task/todo', method: 'GET', params: query });
}

/** 我的已办分页 */
export function pageDoneTasks(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<WorkflowTaskVO>> {
  return http<PageResult<WorkflowTaskVO>>({ url: '/workflow/task/done', method: 'GET', params: query });
}

/** 签收任务 */
export function claimTask(taskId: string): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/claim`, method: 'POST' });
}

/** 审批通过 */
export function approveTask(taskId: string, variables?: Record<string, string | number | boolean>): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/approve`, method: 'POST', data: { variables } });
}

/** 审批驳回 */
export function rejectTask(taskId: string, variables?: Record<string, string | number | boolean>): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/reject`, method: 'POST', data: { variables } });
}

/** 转办 */
export function transferTask(taskId: string, targetUserId: string): Promise<void> {
  return http<void>({ url: `/workflow/task/${taskId}/transfer`, method: 'POST', data: { targetUserId } });
}
