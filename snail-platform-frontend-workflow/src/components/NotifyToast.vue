<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { openCrossApp } from '@/utils/links';
import type { NotifyToastPayload } from '@/composables/useNotifyPush';

/**
 * 消息到达轻提醒:监听 useNotifyPush 广播的 `notify:toast` 合并帧。
 * 与管理端 components/NotifyToast.vue 同构,样式走流程端设计令牌。
 * 口径(与详设 §6.3 对齐):
 * - 同屏最多 3 条,超出丢弃最旧;8s 自动消失,悬停暂停倒计时;
 * - 点击跨端打开管理端通知中心(流程端无收件箱页,正文与已读都在那边,与铃铛"查看全部"同款跳法)。
 */
interface ToastItem {
  id: number;
  text: string;
  timer: number;
}

const auth = useAuthStore();
const items = ref<ToastItem[]>([]);
let seq = 0;
const AUTO_DISMISS_MS = 8000;

function onToast(event: Event): void {
  const { count, titles } = (event as unknown as { detail: NotifyToastPayload }).detail;
  const text = count === 1 ? (titles[0] ?? '新消息') : `收到 ${count} 条新消息`;
  const item: ToastItem = { id: ++seq, text, timer: 0 };
  items.value.push(item);
  // 栈式堆叠上限:超过 3 条丢弃最旧,不堆积
  if (items.value.length > 3) items.value.splice(0, items.value.length - 3);
  armTimer(item);
}

function armTimer(item: ToastItem): void {
  window.clearTimeout(item.timer);
  item.timer = window.setTimeout(() => dismiss(item.id), AUTO_DISMISS_MS);
}

/** 悬停暂停倒计时:清掉定时器即可,离开时 armTimer 重排 */
function pauseTimer(item: ToastItem): void {
  window.clearTimeout(item.timer);
}

function dismiss(id: number): void {
  const item = items.value.find((entry) => entry.id === id);
  if (item) window.clearTimeout(item.timer);
  items.value = items.value.filter((entry) => entry.id !== id);
}

/** 点击进管理端通知中心:与铃铛"查看全部"同款跨端跳法(带 ssotoken) */
function openInbox(): void {
  openCrossApp('admin', auth.token, '/system/notify');
}

onMounted(() => window.addEventListener('notify:toast', onToast));
onBeforeUnmount(() => {
  window.removeEventListener('notify:toast', onToast);
  items.value.forEach((item) => window.clearTimeout(item.timer));
});
</script>

<template>
  <TransitionGroup
    name="toast"
    tag="div"
    class="notify-toast-stack"
    aria-live="polite"
  >
    <div
      v-for="item in items"
      :key="item.id"
      class="notify-toast"
      role="status"
      @mouseenter="pauseTimer(item)"
      @mouseleave="armTimer(item)"
      @click="openInbox"
    >
      <span class="notify-toast-dot"></span>
      <span class="notify-toast-text">{{ item.text }}</span>
      <button
        class="notify-toast-close"
        type="button"
        aria-label="关闭提醒"
        @click.stop="dismiss(item.id)"
      >
        ×
      </button>
    </div>
  </TransitionGroup>
</template>

<style scoped>
.notify-toast-stack {
  position: fixed;
  top: 72px;
  right: 20px;
  z-index: 3000;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}

.notify-toast {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 300px;
  padding: 11px 13px;
  border: 1px solid var(--panel-line, rgba(13, 148, 136, 0.18));
  border-radius: 12px;
  background: var(--panel, #fff);
  box-shadow: 0 14px 34px rgba(15, 118, 110, 0.16);
  cursor: pointer;
  pointer-events: auto;
}

.notify-toast-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand, #0d9488);
  box-shadow: 0 0 0 4px rgba(13, 148, 136, 0.14);
}

.notify-toast-text {
  flex: 1;
  overflow: hidden;
  color: var(--ink, #0f172a);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notify-toast-close {
  flex: none;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted, #64748b);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
}

.notify-toast-close:hover {
  background: var(--panel-soft, #f0fdfa);
  color: var(--ink, #0f172a);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
</style>
