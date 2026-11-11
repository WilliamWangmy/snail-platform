import { del, get, post, put } from './http';

/** 个人档案,与后端 ProfileVO 同口径。 */
export interface Profile {
  userId: number;
  username: string;
  nickname: string;
  email: string | null;
  phone: string | null;
  orgName: string | null;
  tenantName: string | null;
  /** 授权角色名称快照(只读展示,授权调整走用户管理域) */
  roles: string[];
  createTime: string | null;
}

export function fetchProfile(): Promise<Profile> {
  return get<Profile>('/system/profile');
}

/** 自助改资料只开 nickname/email/phone 三字段;org、状态不在契约内,改不到也不需要权限码 */
export function updateProfile(payload: { nickname: string; email: string | null; phone: string | null }): Promise<void> {
  return put<void>('/system/profile', payload);
}

/** 原密码错 5 次锁 10 分钟;成功后令牌版本自增,调用方必须自行清本地会话引导重登 */
export function changePassword(payload: { oldPassword: string; newPassword: string }): Promise<void> {
  return post<void>('/system/profile/password', payload);
}

/** 吊销自己全部会话(含当前设备),不清本地就会在下一个请求被弹回登录页 */
export function forceLogoutSelf(): Promise<void> {
  return post<void>('/system/profile/force-logout');
}

/** 首次登录强制改密:只录新密码,成功后当前会话一并失效,调用方必须引导重新登录 */
export function forceChangePassword(payload: { newPassword: string }): Promise<void> {
  return post<void>('/system/profile/password/force-change', payload);
}

/** 本人第三方绑定:一行=厂商身份x平台账号;openId 原样展示,便于与厂商后台核对 */
export interface UserIdentity {
  id: number;
  source: 'dingtalk' | 'feishu' | 'wecom';
  openId: string;
  unionId: string | null;
  nickname: string | null;
  /** 最近一次经此身份登录时间(ISO 字符串);从未登录过为 null */
  lastLoginTime: string | null;
  createTime: string;
}

/** 本人第三方绑定列表,按绑定先后升序 */
export function fetchMyIdentities(): Promise<UserIdentity[]> {
  return get<UserIdentity[]>('/system/profile/identities');
}

/** 自助解绑:解绑后该第三方身份不能再登录本账号,账号密码登录不受影响 */
export function unbindMyIdentity(id: number): Promise<void> {
  return del<void>(`/system/profile/identities/${id}`);
}
