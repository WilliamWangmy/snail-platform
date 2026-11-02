/**
 * 用户数据（只读消费）：snail-function-system 用户提供，平台不维护。
 * 设计器审批人/候选人下拉数据源。
 */
import { http } from '@/utils/request';
import type { PageQueryParam, PageResult } from '@/types/api';

export interface UserVO {
  userId: number;
  username: string;
  nickname: string;
  orgId?: number;
  status?: number;
}

export function pageUsers(query: PageQueryParam & Record<string, unknown>): Promise<PageResult<UserVO>> {
  return http<PageResult<UserVO>>({ url: '/system/user/page', method: 'GET', params: query });
}
