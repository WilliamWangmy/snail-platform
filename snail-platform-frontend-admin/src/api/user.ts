import { del, get, post, put } from './http';
import type { PageParams, PageResult } from './types';

export interface UserVO {
  userId: number;
  username: string;
  nickname: string;
  email: string | null;
  phone: string | null;
  orgId: number | null;
  status: number;
  remark: string | null;
  createTime: string;
  /** 仅详情接口填充 */
  roleIds: number[] | null;
}

export interface UserQuery extends PageParams {
  orgId?: number | null;
  username?: string;
  nickname?: string;
  status?: number | null;
}

export interface UserCreatePayload {
  username: string;
  password: string;
  nickname: string;
  email?: string | null;
  phone?: string | null;
  orgId?: number | null;
  remark?: string | null;
  roleIds?: number[];
}

export interface UserUpdatePayload {
  nickname: string;
  email?: string | null;
  phone?: string | null;
  orgId?: number | null;
  remark?: string | null;
}

export function pageUsers(query: UserQuery): Promise<PageResult<UserVO>> {
  return get<PageResult<UserVO>>('/system/user/page', query);
}

export function getUser(userId: number): Promise<UserVO> {
  return get<UserVO>(`/system/user/${userId}`);
}

export function createUser(payload: UserCreatePayload): Promise<number> {
  return post<number>('/system/user', payload);
}

export function updateUser(userId: number, payload: UserUpdatePayload): Promise<void> {
  return put<void>(`/system/user/${userId}`, payload);
}

export function resetUserPassword(userId: number, password: string): Promise<void> {
  return put<void>(`/system/user/${userId}/password`, { password });
}

export function changeUserStatus(userId: number, status: number): Promise<void> {
  return put<void>(`/system/user/${userId}/status`, undefined, { status });
}

export function deleteUser(userId: number): Promise<void> {
  return del<void>(`/system/user/${userId}`);
}

export function assignUserRoles(userId: number, roleIds: number[]): Promise<void> {
  return put<void>(`/system/user/${userId}/roles`, { roleIds });
}
