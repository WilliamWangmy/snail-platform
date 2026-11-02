/**
 * 面板远程搜索：用户（审批人/抄送/抢签）与角色（候选组/抄送组）下拉数据源。
 * 每个 el-select 实例独立调用一次工厂函数，互不串选项。
 */
import { ref } from 'vue';

import { pageRoles, type RoleVO } from '@/api/system/role';
import { pageUsers, type UserVO } from '@/api/system/user';

export interface RemoteOption {
  label: string;
  value: string;
}

export interface RemoteSearch {
  options: Ref<RemoteOption[]>;
  remoteMethod: (keyword: string) => Promise<void>;
}

type Ref<T> = import('vue').Ref<T>;

function toUserOptions(rows: UserVO[]): RemoteOption[] {
  return rows.map((u) => ({ label: u.nickname ? `${u.nickname}(${u.username})` : u.username, value: u.username }));
}

function toRoleOptions(rows: RoleVO[]): RemoteOption[] {
  return rows.map((r) => ({ label: r.roleName, value: r.roleCode }));
}

export function useUserRemoteSearch(initial: string[] = []): RemoteSearch {
  const options = ref<RemoteOption[]>(initial.map((v) => ({ label: v, value: v })));
  return {
    options,
    remoteMethod: async (keyword: string) => {
      const page = await pageUsers({ pageNum: 1, pageSize: 50, keyword });
      options.value = toUserOptions(page.rows);
    },
  };
}

export function useRoleRemoteSearch(initial: string[] = []): RemoteSearch {
  const options = ref<RemoteOption[]>(initial.map((v) => ({ label: v, value: v })));
  return {
    options,
    remoteMethod: async (keyword: string) => {
      const page = await pageRoles({ pageNum: 1, pageSize: 50, keyword });
      options.value = toRoleOptions(page.rows);
    },
  };
}
