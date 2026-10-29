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
  nickname: string;
  email?: string | null;
  phone?: string | null;
  /** 后端 @NotNull:必须是本租户状态正常且你可授权的组织 */
  orgId?: number | null;
  remark?: string | null;
  roleIds?: number[];
}

/** 建号/重置口令出参:明文由服务端生成,仅本次响应可见,前端须展示+复制后转交本人 */
export interface UserCreated {
  userId: number;
  password: string;
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

/** 登录名占用查询(全表口径,含已删行);失焦即查用,保存时后端仍会再拦一次防并发抢占 */
export function checkUsername(username: string): Promise<boolean> {
  return get<boolean>('/system/user/username-exists', { username });
}

/** 建号同事务完成授权;口令不传,由服务端按 PasswordPolicy 随机生成并随响应返回明文 */
export function createUser(payload: UserCreatePayload): Promise<UserCreated> {
  return post<UserCreated>('/system/user', payload);
}

/** 只改档案五列;username/password/status 不在契约里,改口令走 reset、改状态走 changeUserStatus */
export function updateUser(userId: number, payload: UserUpdatePayload): Promise<void> {
  return put<void>(`/system/user/${userId}`, payload);
}

/** 重置由服务端按 PasswordPolicy 随机生成新口令并前推令牌版本,明文仅本次响应可见,前端展示+复制 */
export function resetUserPassword(userId: number): Promise<string> {
  // 明文包在 password 键下:后端审计落库走键级脱敏,裸字符串会被原样记进 sys_oper_log
  return put<{ password: string }>(`/system/user/${userId}/password`, undefined).then(
    (body) => body.password,
  );
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
