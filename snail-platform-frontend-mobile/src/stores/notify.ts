import { defineStore } from 'pinia';
import { ref } from 'vue';
import { fetchUnreadCount } from '@/api/notify';

/** TabBar 上"消息"页签的下标(pages.json tabBar.list 顺序)。 */
const MESSAGE_TAB_INDEX = 1;

/** 未读消息全局状态:三个页签都要读,统一走这里并同步 TabBar 角标。 */
export const useNotifyStore = defineStore('notify', () => {
  const unreadCount = ref(0);

  function syncBadge(): void {
    if (unreadCount.value > 0) {
      uni.setTabBarBadge({ index: MESSAGE_TAB_INDEX, text: String(unreadCount.value), fail: () => undefined });
    } else {
      uni.removeTabBarBadge({ index: MESSAGE_TAB_INDEX, fail: () => undefined });
    }
  }

  /** 拉取未读数并同步角标;失败静默(未登录/网络差时不打扰用户)。 */
  async function refresh(): Promise<void> {
    try {
      unreadCount.value = await fetchUnreadCount();
      syncBadge();
    } catch {
      /* 静默失败,保留上次数值 */
    }
  }

  function reset(): void {
    unreadCount.value = 0;
    syncBadge();
  }

  return { unreadCount, refresh, reset };
});
