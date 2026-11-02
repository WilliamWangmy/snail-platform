/**
 * 顶栏 UI 状态（骨架阶段仅占位，后续按需扩展）。
 */
import { ref } from 'vue';
import { defineStore } from 'pinia';

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false);

  return { sidebarCollapsed };
});
