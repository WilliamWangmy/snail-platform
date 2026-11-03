<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';

import AppIcon from '@/components/AppIcon.vue';
import { useAuthStore } from '@/stores/auth';
import { currentTheme, toggleTheme, type FlowTheme } from '@/utils/theme';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const theme = ref<FlowTheme>(currentTheme());

function onToggleTheme(): void {
  theme.value = toggleTheme();
}

const ICONS: Record<string, string> = {
  dashboard: 'layout-dashboard',
  modeler: 'workflow',
  definition: 'layers',
  instance: 'activity',
  task: 'task'
};

const menus = computed(() => {
  const root = router.options.routes.find((item) => item.path === '/');
  return (root?.children ?? [])
    .filter((child) => child.meta?.title && !child.meta?.fullBleed)
    .map((child) => ({
      key: String(child.name ?? child.path),
      path: `/${String(child.path)}`,
      title: String(child.meta?.title ?? ''),
      icon: ICONS[String(child.name)] ?? 'workflow'
    }));
});

const activeKey = computed(() => {
  if (route.name === 'modeler-designer') return 'modeler';
  return String(route.name ?? '');
});

const today = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short'
})
  .format(new Date())
  .replace(/\//g, '.');

const env = import.meta.env.PROD ? 'PROD' : 'DEV';

const avatarText = computed(() => {
  const name = auth.displayName.trim();
  if (!name) return 'SF';
  const parts = name.split(/\s+/);
  if (parts.length > 1) return `${parts[0][0]}${parts[1][0]}`.toUpperCase();
  return name.slice(0, 2).toUpperCase();
});

async function handleLogout(): Promise<void> {
  await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' });
  await auth.logout();
  await router.push('/login');
}
</script>

<template>
  <header class="app-header">
    <div class="app-header-inner">
      <RouterLink
        class="brand"
        to="/dashboard"
        aria-label="Snail Flow 首页"
      >
        <span class="brand-mark">
          <svg
            width="21"
            height="21"
            viewBox="0 0 24 24"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M17.8 6.2A7.2 7.2 0 1 0 18 17.4"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
            />
            <circle
              cx="7.6"
              cy="9.2"
              r="2.1"
              stroke="currentColor"
              stroke-width="1.5"
            />
            <circle
              cx="14.4"
              cy="14.6"
              r="1.6"
              fill="currentColor"
            />
          </svg>
        </span>
        <span class="brand-copy">
          <span class="brand-name">Snail<span>.</span>Flow</span>
          <span class="brand-sub">FLOW CONTROL ROOM</span>
        </span>
      </RouterLink>

      <nav
        class="top-nav"
        aria-label="主导航"
      >
        <RouterLink
          v-for="item in menus"
          :key="item.key"
          class="nav-item"
          :class="{ active: item.key === activeKey }"
          :to="item.path"
          :aria-current="item.key === activeKey ? 'page' : undefined"
        >
          <AppIcon :name="item.icon" />
          <span>{{ item.title }}</span>
        </RouterLink>
      </nav>

      <div class="header-tools">
        <span
          class="header-workspace"
          :title="`工作区 · ${auth.tenantName || '默认租户'}`"
        >
          <span class="live-dot">ONLINE</span>
        </span>
        <span class="top-date">{{ today }}</span>
        <span class="env-pill">{{ env }}</span>
        <button
          class="icon-btn"
          type="button"
          :title="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
          :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
          @click="onToggleTheme"
        >
          <AppIcon :name="theme === 'dark' ? 'sun' : 'moon'" />
        </button>
        <span
          class="header-divider"
          aria-hidden="true"
        ></span>
        <div class="header-user">
          <span class="avatar">{{ avatarText }}</span>
          <span class="header-user-copy">
            <span class="header-user-name">{{ auth.displayName }}</span>
            <span class="header-user-role">{{ auth.tenantName || '流程管理员' }}</span>
          </span>
          <button
            class="icon-btn"
            type="button"
            title="退出登录"
            aria-label="退出登录"
            @click="handleLogout"
          >
            <AppIcon name="log-out" />
          </button>
        </div>
      </div>
    </div>
  </header>
</template>
