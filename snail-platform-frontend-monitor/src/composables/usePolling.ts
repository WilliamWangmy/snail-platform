import { onBeforeUnmount, onMounted, ref } from 'vue';

/**
 * 统一轮询:自动刷新开关(默认开)+ visibilitychange 页面隐藏暂停(§4.8/§6.6/§7.4)。
 * - 开关关:定时器空转不请求;
 * - 页面隐藏:暂停请求,恢复可见时立即补拉一次;
 * - 调用方在 setup 中同步调用,生命周期钩子由本 composable 注册。
 */
export function usePolling(fn: () => void | Promise<void>, intervalMs = 30000) {
  const autoRefresh = ref(true);
  let timer: number | undefined;

  function tick(): void {
    if (autoRefresh.value && !document.hidden) void fn();
  }

  onMounted(() => {
    timer = window.setInterval(tick, intervalMs);
    document.addEventListener('visibilitychange', onVisibility);
  });

  function onVisibility(): void {
    if (!document.hidden && autoRefresh.value) void fn();
  }

  onBeforeUnmount(() => {
    if (timer !== undefined) window.clearInterval(timer);
    document.removeEventListener('visibilitychange', onVisibility);
  });

  return { autoRefresh };
}
