/**
 * 面板远程搜索：用户（审批人/抄送/抢签）与角色（候选组/抄送组）下拉数据源。
 * 每个 el-select 实例独立调用一次工厂函数，互不串选项。
 * 选项 value 为用户/角色 ID（后端引擎口径：assignee=userId、candidateGroups=roleId 的字符串形态），
 * label 为展示名；已存到 BPMN 的初始值按 id 反查名称回显，查不到保留原值。
 * 选中即钉住：Element Plus 远程下拉在选中后会重触发 remote-method 置空列表，
 * 已选项若不钉住会从列表丢失，输入框回退显示裸 value（id）。
 *
 * 回显关键（EP remote 限制）：el-option 的 label 原地升级时，EP 仅在非 remote 下才重算已选文案
 * （useOption 的 watch(currentLabel) 对 remote 直接跳过）。因此初始不能放裸 id 占位项——
 * 否则同名 key 原地换成真名后输入框永远显示 id；必须留空 options，让解析结果以「新 key」
 * 挂载触发 select 内部 setSelected 重算。解析失败/未匹配时输入框自然回退显示裸 value。
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
  /** 选中回调：把当前选中值的 option 钉进列表，值不在列表时先以裸值占位 */
  select: (values: string[]) => void;
}

type Ref<T> = import('vue').Ref<T>;

function toUserOptions(rows: UserVO[]): RemoteOption[] {
  return rows.map((u) => ({ label: u.nickname ? `${u.nickname}(${u.username})` : u.username, value: String(u.userId) }));
}

function toRoleOptions(rows: RoleVO[]): RemoteOption[] {
  return rows.map((r) => ({ label: r.roleName, value: String(r.roleId) }));
}

/** 已选 option 钉住集合 + 列表合并，工厂内各搜索实例共享同一套逻辑 */
function createSticky(options: Ref<RemoteOption[]>) {
  const pinned = new Map<string, RemoteOption>();
  const merge = (list: RemoteOption[]): RemoteOption[] => {
    if (!pinned.size) return list;
    const merged = [...list];
    for (const [value, option] of pinned) {
      if (!merged.some((item) => item.value === value)) merged.unshift(option);
    }
    return merged;
  };
  return {
    merge,
    pin: (values: string[]) => {
      for (const value of values) {
        const found = options.value.find((item) => item.value === value);
        pinned.set(value, found ?? { label: value, value });
      }
    },
    /** 回显解析完成后以真实名称落钉（覆盖此前可能的裸值占位钉住） */
    resolve: (resolved: RemoteOption[]) => {
      for (const option of resolved) pinned.set(option.value, option);
    },
  };
}

export function useUserRemoteSearch(initial: string[] = []): RemoteSearch {
  // 初始不放裸 id 占位项：remote 下占位项与解析结果同 key，label 原地升级不会刷新已选文案（见文件头注释）
  const options = ref<RemoteOption[]>([]);
  const sticky = createSticky(options);
  let searched = false;
  if (initial.length) {
    // 用户接口没有按 id 批量查：回显阶段拉一页全量按 id 匹配，查不到保留原值
    pageUsers({ pageNum: 1, pageSize: 200 })
      .then((page) => {
        const resolved = toUserOptions(page.rows).filter((o) => initial.includes(o.value));
        sticky.resolve(resolved);
        // 已有搜索结果时不覆盖列表，仅把钉住项并入
        options.value = sticky.merge(searched ? options.value : resolved);
      })
      .catch(() => undefined);
  }
  return {
    options,
    remoteMethod: async (keyword: string) => {
      searched = true;
      // 用户接口没有统一 keyword：先按昵称模糊匹配，无结果再按登录名匹配
      let page = await pageUsers({ pageNum: 1, pageSize: 50, nickname: keyword });
      if (page.rows.length === 0 && keyword) {
        page = await pageUsers({ pageNum: 1, pageSize: 50, username: keyword });
      }
      options.value = sticky.merge(toUserOptions(page.rows));
    },
    select: (values: string[]) => {
      if (!values.length) return;
      sticky.pin(values);
      options.value = sticky.merge(options.value);
    },
  };
}

export function useRoleRemoteSearch(initial: string[] = []): RemoteSearch {
  // 初始不放裸 id 占位项，理由同 useUserRemoteSearch
  const options = ref<RemoteOption[]>([]);
  const sticky = createSticky(options);
  let searched = false;
  if (initial.length) {
    pageRoles({ pageNum: 1, pageSize: 200 })
      .then((page) => {
        const resolved = toRoleOptions(page.rows).filter((o) => initial.includes(o.value));
        sticky.resolve(resolved);
        options.value = sticky.merge(searched ? options.value : resolved);
      })
      .catch(() => undefined);
  }
  return {
    options,
    remoteMethod: async (keyword: string) => {
      searched = true;
      const page = await pageRoles({ pageNum: 1, pageSize: 50, keyword });
      options.value = sticky.merge(toRoleOptions(page.rows));
    },
    select: (values: string[]) => {
      if (!values.length) return;
      sticky.pin(values);
      options.value = sticky.merge(options.value);
    },
  };
}
