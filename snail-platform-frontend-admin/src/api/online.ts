import { get, post } from './http';
import type { PageParams, PageResult } from './types';

/** 在线用户,与后端 OnlineSessionVO 同口径。 */
export interface OnlineSession {
  userId: number;
  username: string | null;
  /** 心跳当时从登录人快照带出的显示名,改名后等下一次心跳才刷新 */
  nickname: string | null;
  ip: string | null;
  userAgent: string | null;
  firstSeen: string | null;
  lastActive: string | null;
}

export interface OnlineQuery extends PageParams {
  /** 后端对登录名与显示名都做包含匹配,内存裁剪因此支持不了复杂条件 */
  keyword?: string;
}

/** 数据源是 Redis 会话快照,不是档案:30 分钟无心跳即从索引消失,列里的 org/角色一律没有 */
export function pageOnline(query: OnlineQuery): Promise<PageResult<OnlineSession>> {
  return get<PageResult<OnlineSession>>('/system/online/page', query);
}

/** 令牌版本自增是真吊销,Redis 会话键清理只是让列表即时消失;踢自己会被后端拒 */
export function kickOnline(userId: number): Promise<void> {
  return post<void>(`/system/online/${userId}/kick`);
}
