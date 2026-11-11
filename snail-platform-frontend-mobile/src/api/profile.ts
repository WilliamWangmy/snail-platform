import { get, post, put } from '@/adapter/request';

/**
 * 个人中心(端点与 admin/src/api/profile.ts 完全同口径)。
 * 注意:改密成功后令牌版本自增,调用方必须清本地会话并引导重登。
 */

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

/** 自助改资料只开 nickname/email/phone 三字段。 */
export function updateProfile(payload: { nickname: string; email: string | null; phone: string | null }): Promise<void> {
  return put<void>('/system/profile', payload);
}

/** 原密码错 5 次锁 10 分钟;成功后令牌版本自增。 */
export function changePassword(payload: { oldPassword: string; newPassword: string }): Promise<void> {
  return post<void>('/system/profile/password', payload);
}

/** 吊销自己全部会话(含当前设备)。 */
export function forceLogoutSelf(): Promise<void> {
  return post<void>('/system/profile/force-logout');
}

/** 首次登录强制改密:只录新密码,成功后当前会话一并失效。 */
export function forceChangePassword(payload: { newPassword: string }): Promise<void> {
  return post<void>('/system/profile/password/force-change', payload);
}
