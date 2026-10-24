import { download, get } from './http';
import type { PageParams, PageResult } from './types';

export interface OperLogListVO {
  id: number;
  tenantId: number;
  module: string;
  /** 1 新增 2 修改 3 删除 4 导出 5 导入 9 其他 */
  businessType: number;
  method: string;
  requestMethod: string;
  operator: string;
  orgId: number | null;
  ip: string | null;
  url: string;
  status: number;
  errorMsg: string | null;
  costTime: number | null;
  traceId: string | null;
  operTime: string;
}

export interface OperLogDetailVO extends OperLogListVO {
  param: string | null;
  result: string | null;
}

export interface OperLogQuery extends PageParams {
  tenantId?: number | null;
  module?: string;
  businessType?: number | null;
  operator?: string;
  status?: number | null;
  beginTime?: string;
  endTime?: string;
}

export interface LoginLogVO {
  id: number;
  tenantId: number;
  username: string;
  userId: number | null;
  ip: string | null;
  browser: string | null;
  os: string | null;
  status: number;
  msg: string | null;
  loginTime: string;
}

export interface LoginLogQuery extends PageParams {
  tenantId?: number | null;
  username?: string;
  ip?: string;
  status?: number | null;
  beginTime?: string;
  endTime?: string;
}

export function pageOperLogs(query: OperLogQuery): Promise<PageResult<OperLogListVO>> {
  return get<PageResult<OperLogListVO>>('/system/log/oper/page', query);
}

export function getOperLog(id: number): Promise<OperLogDetailVO> {
  return get<OperLogDetailVO>(`/system/log/oper/${id}`);
}

export function exportOperLogs(query: OperLogQuery): Promise<void> {
  return download('/system/log/oper/export', query, '操作日志.xlsx');
}

export function pageLoginLogs(query: LoginLogQuery): Promise<PageResult<LoginLogVO>> {
  return get<PageResult<LoginLogVO>>('/system/log/login/page', query);
}

export function exportLoginLogs(query: LoginLogQuery): Promise<void> {
  return download('/system/log/login/export', query, '登录日志.xlsx');
}
