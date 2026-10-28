import { del, get, post, put } from './http';
import type { PageParams, PageResult } from './types';

export interface UserVO {
  userId: number;
  /** 登录名,全表唯一且建后不可改 */
  username: string;
  nickname: string;
  email: string | null;
  phone: string | null;
  /** 一用户一主组织 */
  orgId: number | null;
  /** 0 正常 1 停用 */
  status: number;
  remark: string | null;
  createTime: string;
  /** 仅详情接口填充 */
  roleIds: number[] | null;
}

export interface UserQuery extends PageParams {
  /** 精确匹配本级组织,不含下级;要连下级一起筛靠数据权限档位或 org 的 subtree 接口 */
  orgId?: number | null;
  username?: string;
  nickname?: string;
  /** 0/1,不传不限 */
  status?: number | null;
}

export interface UserCreatePayload {
  /** 全表唯一(跨租户也算占用),建后不可改;查重由后端兜底 */
  username: string;
  /** 初始口令明文,后端 BCrypt 落库 */
  password: string;
  nickname: string;
  email?: string | null;
  phone?: string | null;
  /** 后端 @NotNull:必须是本租户状态正常且你可授权的组织 */
  orgId?: number | null;
  remark?: string | null;
  roleIds?: number[];
}

export interface UserUpdatePayload {
  nickname: string;
  email?: string | null;
  phone?: string | null;
  /** 调岗必过组织两道闸:目标组织要存在启用,且在你当前数据权限可授权范围内 */
  orgId?: number | null;
  remark?: string | null;
}

/** 列表投影:后端按你的数据权限过滤,roleIds 恒为 null,要看授权集得走 getUser */
export function pageUsers(query: UserQuery): Promise<PageResult<UserVO>> {
  return get<PageResult<UserVO>>('/system/user/page', query);
}

/** 详情比列表多带 roleIds;目标用户不在可见集内时后端拒绝 */
export function getUser(userId: number): Promise<UserVO> {
  return get<UserVO>(`/system/user/${userId}`);
}

/** roleIds 可空,提交即在建号同事务内完成授权;口令强度由后端 PasswordPolicy 单点判 */
export function createUser(payload: UserCreatePayload): Promise<number> {
  return post<number>('/system/user', payload);
}

/** 只改档案五列;username/password/status 不在契约里,改口令走 reset、改状态走 changeUserStatus */
export function updateUser(userId: number, payload: UserUpdatePayload): Promise<void> {
  return put<void>(`/system/user/${userId}`, payload);
}

/** 重置同时前推令牌版本:该用户全部存量设备下一次请求即 401,需引导用新口令重登 */
export function resetUserPassword(userId: number, password: string): Promise<void> {
  return put<void>(`/system/user/${userId}/password`, { password });
}

/** status 走 query 参数而非请求体;停用即刻失效存量会话,且不允许停用自己 */
export function changeUserStatus(userId: number, status: number): Promise<void> {
  return put<void>(`/system/user/${userId}/status`, undefined, { status });
}

/** 逻辑删 + 同事务清空授权;不允许删自己,登录名此后仍被占用,不可复用 */
export function deleteUser(userId: number): Promise<void> {
  return del<void>(`/system/user/${userId}`);
}

/** 全量覆盖,提交的集合即最终态(空数组即清空);档位宽于你自身的角色会被拒 */
export function assignUserRoles(userId: number, roleIds: number[]): Promise<void> {
  return put<void>(`/system/user/${userId}/roles`, { roleIds });
}

/** 强制下线:令牌版本自增,该用户全部设备下一次请求即 401。 */
export function forceLogoutUser(userId: number): Promise<void> {
  return post<void>(`/system/user/${userId}/force-logout`);
}
