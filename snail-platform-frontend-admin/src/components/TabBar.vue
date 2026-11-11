<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import { useTabsStore, type TabItem } from '@/stores/tabs';

const route = useRoute();
const router = useRouter();
const tabs = useTabsStore();

const scrollRef = ref<HTMLElement | null>(null);

/** 激活页签滚动到可视区,避免页签多了找不到当前项。 */
watch(
  () => route.fullPath,
  async () => {
    await nextTick();
    scrollRef.value?.querySelector('.tab-item.is-active')?.scrollIntoView({ behavior: 'smooth', inline: 'nearest', block: 'nearest' });
  },
  { immediate: true },
);

function isActive(tab: TabItem): boolean {
  return tab.fullPath === route.fullPath;
}

function open(tab: TabItem): void {
  if (!isActive(tab)) void router.push(tab.fullPath);
}

/** 中键直接关闭,与浏览器页签习惯一致。 */
function onAuxclick(tab: TabItem, event: MouseEvent): void {
  if (event.button === 1) close(tab);
}

/** 关闭后若关的是当前页,自动落到相邻页签(先右后左,兜底工作台)。 */
function close(tab: TabItem): void {
  const active = isActive(tab);
  const next = tabs.removeTab(tab.fullPath);
  if (active && next.fullPath !== route.fullPath) void router.push(next.fullPath);
}

/* ------------------------- 右键上下文菜单 ------------------------- */

const menuVisible = ref(false);
const menuX = ref(0);
const menuY = ref(0);
const menuTab = ref<TabItem | null>(null);

function openMenu(tab: TabItem, event: MouseEvent): void {
  menuTab.value = tab;
  menuVisible.value = true;
  // 先取尺寸再定位,贴边时向内收,防菜单溢出视口
  nextTick(() => {
    const el = document.querySelector('.tab-menu') as HTMLElement | null;
    const width = el?.offsetWidth ?? 150;
    const height = el?.offsetHeight ?? 150;
    menuX.value = Math.min(event.clientX, window.innerWidth - width - 8);
    menuY.value = Math.min(event.clientY, window.innerHeight - height - 8);
  });
}

function closeMenu(): void {
  menuVisible.value = false;
}

type MenuAction = 'refresh' | 'close' | 'others' | 'right' | 'all';

function handleMenu(action: MenuAction): void {
  const tab = menuTab.value;
  closeMenu();
  if (!tab) return;
  switch (action) {
    case 'refresh':
      refresh(tab);
      break;
    case 'close':
      close(tab);
      break;
    case 'others':
      tabs.closeOthers(tab.fullPath);
      if (!isActive(tab)) void router.push(tab.fullPath);
      break;
    case 'right':
      tabs.closeRight(tab.fullPath);
      // 当前页若被划入右侧而被关掉,回到基准页签
      if (!tabs.tabs.some((item) => item.fullPath === route.fullPath)) void router.push(tab.fullPath);
      break;
    case 'all': {
      const target = tabs.closeAll();
      if (route.fullPath !== target) void router.push(target);
      break;
    }
  }
}

/** 刷新:激活页签经 /redirect 跳一跳强制重载;非激活页签只清缓存,下次进入自然重建。 */
function refresh(tab: TabItem): void {
  if (isActive(tab)) {
    tabs.markRefresh(tab.viewName);
    void router.replace(`/redirect${tab.fullPath}`);
  } else {
    void tabs.dropCache(tab.viewName);
  }
}

function onDocMousedown(event: MouseEvent): void {
  const target = event.target as HTMLElement;
  if (!target.closest('.tab-menu') && !target.closest('.tab-item')) closeMenu();
}

onMounted(() => document.addEventListener('mousedown', onDocMousedown));
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocMousedown);
  closeMenu();
});
</script>

<template>
  <div class="tab-bar">
    <div
      ref="scrollRef"
      class="tab-scroll"
    >
      <div
        v-for="tab in tabs.tabs"
        :key="tab.fullPath"
        class="tab-item"
        :class="{ 'is-active': isActive(tab), 'is-affix': tab.affix }"
        role="tab"
        :aria-selected="isActive(tab)"
        :title="tab.title"
        @click="open(tab)"
        @auxclick="onAuxclick(tab, $event)"
        @contextmenu.prevent="openMenu(tab, $event)"
      >
        <span
          class="tab-dot"
          aria-hidden="true"
        ></span>
        <span class="tab-title">{{ tab.title }}</span>
        <button
          v-if="!tab.affix"
          class="tab-close"
          type="button"
          :aria-label="`关闭 ${tab.title}`"
          @click.stop="close(tab)"
        >
          <AppIcon
            name="close"
            :size="11"
          />
        </button>
      </div>
    </div>

    <Teleport to="body">
      <div
        v-if="menuVisible && menuTab"
        class="tab-menu"
        :style="{ left: `${menuX}px`, top: `${menuY}px` }"
      >
        <button
          class="tab-menu-item"
          type="button"
          @click="handleMenu('refresh')"
        >
          <AppIcon
            name="refresh"
            :size="13"
          />
          刷新页签
        </button>
        <button
          v-if="!menuTab.affix"
          class="tab-menu-item"
          type="button"
          @click="handleMenu('close')"
        >
          <AppIcon
            name="close"
            :size="13"
          />
          关闭页签
        </button>
        <button
          class="tab-menu-item"
          type="button"
          @click="handleMenu('others')"
        >
          <AppIcon
            name="columns"
            :size="13"
          />
          关闭其他
        </button>
        <button
          class="tab-menu-item"
          type="button"
          @click="handleMenu('right')"
        >
          <AppIcon
            name="chevron-right"
            :size="13"
          />
          关闭右侧
        </button>
        <button
          class="tab-menu-item"
          type="button"
          @click="handleMenu('all')"
        >
          <AppIcon
            name="trash"
            :size="13"
          />
          全部关闭
        </button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.tab-bar {
  position: sticky;
  top: 68px;
  z-index: 25;
  padding: 8px 30px 0;
  background: linear-gradient(180deg, rgba(var(--app-card-rgb), 0.96), rgba(var(--app-card-rgb), 0.86));
  border-bottom: 1px solid var(--app-line);
  backdrop-filter: blur(14px);
}

.tab-scroll {
  display: flex;
  align-items: center;
  gap: 7px;
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
}

.tab-scroll::-webkit-scrollbar {
  display: none;
}

.tab-item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  max-width: 200px;
  height: 32px;
  flex: none;
  padding: 0 10px;
  border: 1px solid var(--app-line);
  border-radius: 9px;
  background: var(--app-card);
  color: var(--app-muted);
  font-size: 12px;
  cursor: pointer;
  user-select: none;
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
}

.tab-item:hover {
  color: var(--app-ink);
  border-color: color-mix(in srgb, var(--app-primary) 38%, var(--app-line));
}

.tab-item.is-active {
  border-color: color-mix(in srgb, var(--app-primary) 45%, transparent);
  background: var(--app-soft);
  color: var(--app-ink);
  font-weight: 600;
  box-shadow: 0 2px 10px rgba(var(--app-primary-rgb), 0.13);
}

.tab-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--app-muted);
  transition: background 0.15s ease, box-shadow 0.15s ease;
}

.tab-item.is-active .tab-dot {
  background: var(--app-primary);
  box-shadow: 0 0 8px rgba(var(--app-primary-rgb), 0.55);
}

.tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tab-close {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  flex: none;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  cursor: pointer;
  opacity: 0.55;
  transition: opacity 0.15s ease, background 0.15s ease;
}

.tab-close:hover {
  opacity: 1;
  background: rgba(var(--app-danger-rgb), 0.14);
  color: var(--app-danger);
}

.tab-menu {
  position: fixed;
  z-index: 2000;
  min-width: 132px;
  padding: 5px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-card);
  box-shadow: var(--app-shadow);
}

.tab-menu-item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--app-ink);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s ease;
}

.tab-menu-item:hover {
  background: var(--app-soft-2);
}

@media (max-width: 1024px) {
  .tab-bar {
    padding: 8px 18px 0;
  }
}
</style>
