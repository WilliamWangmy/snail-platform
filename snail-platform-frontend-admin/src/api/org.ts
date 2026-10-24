import { del, get, post, put } from './http';

export interface OrgNode {
  orgId: number;
  parentId: number;
  name: string;
  ancestors: string;
  sort: number;
  status: number;
  remark: string | null;
  children: OrgNode[];
}

export interface OrgCreatePayload {
  parentId: number;
  name: string;
  sort?: number;
  remark?: string | null;
}

export interface OrgUpdatePayload {
  name: string;
  sort?: number;
  status?: number;
  remark?: string | null;
}

export function fetchOrgTree(): Promise<OrgNode[]> {
  return get<OrgNode[]>('/system/org/tree');
}

export function fetchSubtreeIds(orgId: number): Promise<number[]> {
  return get<number[]>(`/system/org/${orgId}/subtree`);
}

export function createOrg(payload: OrgCreatePayload): Promise<number> {
  return post<number>('/system/org', payload);
}

export function updateOrg(orgId: number, payload: OrgUpdatePayload): Promise<void> {
  return put<void>(`/system/org/${orgId}`, payload);
}

export function moveOrg(orgId: number, newParentId: number): Promise<void> {
  return put<void>(`/system/org/${orgId}/move`, { newParentId });
}

export function deleteOrg(orgId: number): Promise<void> {
  return del<void>(`/system/org/${orgId}`);
}
