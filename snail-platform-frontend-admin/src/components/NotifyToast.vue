<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { NotifyToastPayload } from '@/composables/useNotifyPush';

/**
 * 消息到达轻提醒:监听 useNotifyPush 广播的 `notify:toast` 合并帧。
 * 降噪口径(与详设 §6.3 一致):
 * - 用户正处收件箱页时不弹(列表已实时刷新,再弹即重复打扰),只靠角标;
 * - 同屏最多 3 条,超出丢弃最旧;8s 自动消失,悬停暂停倒计时;
 * - 点击进收件箱(列表点行即开详情并标已读);关闭按钮只关本条,不清未读。
 */
interface ToastItem {
  id: number;
  text: string;
  timer: number;
}

const route = useRoute();
const router = useRouter();
const items = ref<ToastItem[]>([]);
let seq = 0;
const AUTO_DISMISS_MS = 8000;

function onToast(event: Event): void {
  if (route.path.startsWith('/system/notify')) return;
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

/** 点击进收件箱:详情与已读动作都在列表行上完成,toast 只负责"有消息"这件事 */
function openInbox(): void {
  void router.push('/system/notify');
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
  top: 76px;
  right: 24px;
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
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-card, #fff);
  box-shadow: 0 14px 34px rgba(0, 0, 0, 0.14);
  cursor: pointer;
  pointer-events: auto;
}

.notify-toast-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-primary);
  box-shadow: 0 0 0 4px rgba(var(--app-primary-rgb), 0.12);
}

.notify-toast-text {
  flex: 1;
  overflow: hidden;
  color: var(--app-ink, #1f2937);
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
  color: var(--app-muted, #9ca3af);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
}

.notify-toast-close:hover {
  background: var(--app-soft, #f3f4f6);
  color: var(--app-ink, #1f2937);
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
