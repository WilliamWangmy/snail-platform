/**
 * 账号自助：个人档案与改密，端点与管理端 api/profile.ts 同口径（同一后端 ProfileController）。
 */
import { http } from '@/utils/request';

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
  return http<Profile>({ url: '/system/profile', method: 'GET' });
}

/** 自助改资料只开 nickname/email/phone 三字段;org、状态不在契约内,改不到也不需要权限码 */
export function updateProfile(payload: { nickname: string; email: string | null; phone: string | null }): Promise<void> {
  return http<void>({ url: '/system/profile', method: 'PUT', data: payload });
}

/** 原密码错 5 次锁 10 分钟;成功后令牌版本自增,调用方必须自行清本地会话引导重登 */
export function changePassword(payload: { oldPassword: string; newPassword: string }): Promise<void> {
  return http<void>({ url: '/system/profile/password', method: 'POST', data: payload });
}
