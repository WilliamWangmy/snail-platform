import { get, post, put } from './http';

/** 个人档案,与后端 ProfileVO 同口径。 */
export interface Profile {
  userId: number;
  username: string;
  nickname: string;
  email: string | null;
  phone: string | null;
  orgName: string | null;
  tenantName: string | null;
  createTime: string | null;
}

export function fetchProfile(): Promise<Profile> {
  return get<Profile>('/system/profile');
}

export function updateProfile(payload: { nickname: string; email: string | null; phone: string | null }): Promise<void> {
  return put<void>('/system/profile', payload);
}

export function changePassword(payload: { oldPassword: string; newPassword: string }): Promise<void> {
  return post<void>('/system/profile/password', payload);
}

export function forceLogoutSelf(): Promise<void> {
  return post<void>('/system/profile/force-logout');
}
