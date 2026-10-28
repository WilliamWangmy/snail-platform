import { del, get, post, put } from './http';

export interface OrgNode {
  orgId: number;
  /** 父组织 ID,根为 0 */
  parentId: number;
  name: string;
  /** 祖级路径,逗号分隔如 0,1,5;由服务端维护,前端只读 */
  ancestors: string;
  sort: number;
  /** 0 正常 1 停用 */
  status: number;
  remark: string | null;
  /** 服务端按 parentId 组好的子节点,整棵返回、不分页 */
  children: OrgNode[];
}

export interface OrgCreatePayload {
  /** 须为树内已存在的节点;传 0 新增根会被后端拒(根由租户开通模板生成) */
  parentId: number;
  name: string;
  sort?: number;
  remark?: string | null;
}

export interface OrgUpdatePayload {
  name: string;
  sort?: number;
  /** 不传则保持原状态;停用只拦后续被选作父级 */
  status?: number;
  /** 提交 null 或空串即清空备注 */
  remark?: string | null;
}

/** 整棵返回、不分页;只含当前数据权限可见的节点,父被过滤掉的会升为根层 */
export function fetchOrgTree(): Promise<OrgNode[]> {
  return get<OrgNode[]>('/system/org/tree');
}

/** 含自身;orgId 不在当前数据权限可见集内时后端拒绝 */
export function fetchSubtreeIds(orgId: number): Promise<number[]> {
  return get<number[]>(`/system/org/${orgId}/subtree`);
}

/** 只新增子节点;状态不接受提交,新节点一律为正常 */
export function createOrg(payload: OrgCreatePayload): Promise<number> {
  return post<number>('/system/org', payload);
}

/** 只改属性;改父级不走这里,用 moveOrg */
export function updateOrg(orgId: number, payload: OrgUpdatePayload): Promise<void> {
  return put<void>(`/system/org/${orgId}`, payload);
}

/** 服务端重写整棵子树的 ancestors;移入自身子树或移动根会被拒 */
export function moveOrg(orgId: number, newParentId: number): Promise<void> {
  return put<void>(`/system/org/${orgId}/move`, { newParentId });
}

/** 有下级或仍挂着用户时后端拒绝 */
export function deleteOrg(orgId: number): Promise<void> {
  return del<void>(`/system/org/${orgId}`);
}
