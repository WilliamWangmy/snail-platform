import { get } from '@/adapter/request';
import type { PageParams, PageResult } from './types';

/** 在线会话(端点与 admin/src/api/online.ts 同口径;移动端仅用 total 做数据卡)。 */
export interface OnlineQuery extends PageParams {
  username?: string;
}

export interface OnlineSession {
  sessionId: number;
  userId: number;
  username: string;
  nickname: string | null;
  tenantId: number;
  loginTime: string | null;
  loginIp: string | null;
}

export function pageOnline(query: OnlineQuery): Promise<PageResult<OnlineSession>> {
  return get<PageResult<OnlineSession>>('/system/online/page', query);
}
