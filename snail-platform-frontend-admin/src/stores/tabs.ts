import { computed, nextTick, ref } from 'vue';
import type { RouteLocationNormalized } from 'vue-router';
import { defineStore } from 'pinia';

/** 单个页签:fullPath 是唯一键(path+query),affix 页签不可关闭。 */
export interface TabItem {
  path: string;
  fullPath: string;
  title: string;
  affix?: boolean;
  /** 组件名(路由 meta.viewName),KeepAlive include 依它匹配缓存。 */
  viewName?: string;
}

/** 固定页签:工作台常驻第一位,登录后即使直达其他页也存在。 */
const AFFIX_TAB: TabItem = { path: '/home', fullPath: '/home', title: '工作台', affix: true, viewName: 'HomeView' };

/**
 * 多页签状态:
 * - tabs 为打开的页签序列,切换/关闭/右键批量操作都基于它;
 * - skipCache 里的组件名在 KeepAlive include 中被临时摘除,
 *   用于"刷新页签":摘除 → 经 /redirect 跳一跳(旧实例被裁剪) → 中转页恢复 → 全新挂载再入缓存。
 */
export const useTabsStore = defineStore('tabs', () => {
  const tabs = ref<TabItem[]>([{ ...AFFIX_TAB }]);
  const skipCache = ref<string[]>([]);

  /** KeepAlive include:页签对应组件名去重后减去正在刷新的。 */
  const cachedViews = computed(() =>
    [...new Set(tabs.value.map((tab) => tab.viewName).filter((name): name is string => !!name && !skipCache.value.includes(name)))],
  );

  /** 路由进入布局即登记页签;/redirect 中转与显式 hideTab 的路由不占页签。 */
  function addTab(route: RouteLocationNormalized): void {
    if (route.path === '/redirect' || route.meta.hideTab) return;
    const item: TabItem = {
      path: route.path,
      fullPath: route.fullPath,
      title: (route.meta.title as string | undefined) ?? route.path,
      affix: route.meta.affix === true,
      viewName: route.meta.viewName as string | undefined,
    };
    const existing = tabs.value.find((tab) => tab.fullPath === item.fullPath);
    if (existing) {
      existing.title = item.title;
      return;
    }
    tabs.value.push(item);
  }

  /** 关闭页签;若关的是激活页,调用方按返回的相邻页签跳转(先右后左,无则回工作台)。 */
  function removeTab(fullPath: string): TabItem {
    const index = tabs.value.findIndex((tab) => tab.fullPath === fullPath);
    if (index < 0) return tabs.value[0]!;
    if (tabs.value[index]?.affix) return tabs.value[index]!;
    tabs.value.splice(index, 1);
    return tabs.value[Math.min(index, tabs.value.length - 1)] ?? { ...AFFIX_TAB };
  }

  /** 关闭其他:仅保留固定页签与指定页签。 */
  function closeOthers(fullPath: string): void {
    tabs.value = tabs.value.filter((tab) => tab.affix || tab.fullPath === fullPath);
  }

  /** 关闭右侧:指定页签之后的全关。 */
  function closeRight(fullPath: string): void {
    const index = tabs.value.findIndex((tab) => tab.fullPath === fullPath);
    if (index >= 0) tabs.value.splice(index + 1);
  }

  /** 全部关闭:只留固定页签,返回应跳转的地址。 */
  function closeAll(): string {
    tabs.value = tabs.value.filter((tab) => tab.affix);
    return tabs.value[0]?.fullPath ?? AFFIX_TAB.fullPath;
  }

  /**
   * 刷新激活页签:把组件名从 include 摘除并跳 /redirect,
   * 旧缓存实例随之被裁剪;中转页挂载时 restoreCache 恢复,目标页全新挂载。
   */
  function markRefresh(viewName?: string): void {
    if (viewName && !skipCache.value.includes(viewName)) skipCache.value.push(viewName);
  }

  /** 非激活页签刷新:无跳转,摘除缓存等下一帧恢复,实例下次进入时重建。 */
  async function dropCache(viewName?: string): Promise<void> {
    if (!viewName) return;
    markRefresh(viewName);
    await nextTick();
    skipCache.value = skipCache.value.filter((name) => name !== viewName);
  }

  /** /redirect 中转页回调:跳回目标前恢复 include,让目标页以新实例挂载并重新入缓存。 */
  function restoreCache(): void {
    skipCache.value = [];
  }

  /** 退出登录清场,防止换账号残留上一账号的页签。 */
  function reset(): void {
    tabs.value = [{ ...AFFIX_TAB }];
    skipCache.value = [];
  }

  return { tabs, skipCache, cachedViews, addTab, removeTab, closeOthers, closeRight, closeAll, markRefresh, dropCache, restoreCache, reset };
});
