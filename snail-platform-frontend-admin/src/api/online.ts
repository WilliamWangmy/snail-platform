import { get, post } from './http';
import type { PageParams, PageResult } from './types';

/** 在线用户,与后端 OnlineSessionVO 同口径。 */
export interface OnlineSession {
  userId: number;
  username: string | null;
  ip: string | null;
  userAgent: string | null;
  firstSeen: string | null;
  lastActive: string | null;
}

export interface OnlineQuery extends PageParams {
  keyword?: string;
}

export function pageOnline(query: OnlineQuery): Promise<PageResult<OnlineSession>> {
  return get<PageResult<OnlineSession>>('/system/online/page', query);
}

export function kickOnline(userId: number): Promise<void> {
  return post<void>(`/system/online/${userId}/kick`);
}
