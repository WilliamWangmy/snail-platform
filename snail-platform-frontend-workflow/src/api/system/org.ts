/**
 * 组织数据（只读消费）：snail-function-system 提供，平台不维护。
 */
import { http } from '@/utils/request';

export interface OrgNodeVO {
  orgId: number;
  orgName: string;
  parentId?: number;
  children?: OrgNodeVO[];
}

export function getOrgTree(): Promise<OrgNodeVO[]> {
  return http<OrgNodeVO[]>({ url: '/system/org/tree', method: 'GET' });
}
