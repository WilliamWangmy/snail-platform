import { ref } from 'vue';
import { defineStore } from 'pinia';

const TAB_MODE_KEY = 'snail_admin_tab_mode_v1';

/** '1'/'缺省' 为多页签,显式 '0' 才是单页,老用户升级默认拿页签模式。 */
function readStoredTabMode(): boolean {
  try {
    return localStorage.getItem(TAB_MODE_KEY) !== '0';
  } catch {
    return true;
  }
}

/** 布局偏好:多页签(菜单按 tab 展示)与单页(点菜单直接替换当前页)切换,持久化到本地。 */
export const useLayoutStore = defineStore('layout', () => {
  const tabMode = ref(readStoredTabMode());

  function setTabMode(value: boolean): void {
    tabMode.value = value;
    try {
      localStorage.setItem(TAB_MODE_KEY, value ? '1' : '0');
    } catch {
      /* 存储不可用时仅本次会话生效 */
    }
  }

  return { tabMode, setTabMode };
});
