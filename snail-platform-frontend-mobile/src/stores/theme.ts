import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

const STORAGE_KEY = 'snail_theme_dark';

/** 深色模式偏好:本地持久化;wot 组件经 wd-config-provider 切换,页面自绘样式走 CSS 变量。 */
export const useThemeStore = defineStore('theme', () => {
  const dark = ref<boolean>(uni.getStorageSync(STORAGE_KEY) === '1');

  const isDark = computed(() => dark.value);
  /** wot-design-uni 的 ConfigProvider theme 值。 */
  const wotTheme = computed<'light' | 'dark'>(() => (dark.value ? 'dark' : 'light'));

  function toggle(): void {
    dark.value = !dark.value;
    uni.setStorageSync(STORAGE_KEY, dark.value ? '1' : '0');
  }

  return { dark, isDark, wotTheme, toggle };
});
