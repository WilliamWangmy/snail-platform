<script setup lang="ts">
/**
 * 顶栏：Logo + 一级菜单（路由 meta 驱动）+ 租户/用户/登出。
 * 固定 56px 高，不参与内容区缩放。
 */
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';

import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

/** 顶部一级菜单 = 根路由 '/' 的子路由（有 title 即视为菜单项） */
const menus = computed(() => {
  const root = router.options.routes.find((item) => item.path === '/');
  return (root?.children ?? []).filter((child) => child.meta?.title);
});

const activeMenu = computed(() => `/${route.path.split('/')[1] ?? ''}`);

async function handleLogout(): Promise<void> {
  await ElMessageBox.confirm('确定退出登录？', '提示', { type: 'warning' });
  await auth.logout();
  await router.push('/login');
}
</script>

<template>
  <header class="top-nav">
    <div class="brand">
      <span class="brand-mark"></span>
      <span class="brand-name">Snail 流程管理平台</span>
    </div>

    <nav class="menu">
      <RouterLink
        v-for="menu in menus"
        :key="menu.path"
        class="menu-item"
        :class="{ active: activeMenu === `/${String(menu.path)}` }"
        :to="`/${String(menu.path)}`"
      >
        {{ menu.meta?.title }}
      </RouterLink>
    </nav>

    <div class="user-area">
      <span
        v-if="auth.tenantName"
        class="tenant"
      >{{ auth.tenantName }}</span>
      <span class="username">{{ auth.displayName }}</span>
      <el-button
        link
        type="danger"
        @click="handleLogout"
      >
        登出
      </el-button>
    </div>
  </header>
</template>

<style scoped lang="scss">
.top-nav {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 32px;
  height: 56px;
  padding: 0 24px;
  border-bottom: 1px solid var(--snail-border);
  background: var(--snail-bg-nav);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;

  .brand-mark {
    width: 14px;
    height: 14px;
    border-radius: 3px;
    background: var(--snail-accent);
  }

  .brand-name {
    font-size: 16px;
    font-weight: 500;
    color: var(--snail-text-primary);
  }
}

.menu {
  display: flex;
  flex: 1;
  gap: 8px;
  height: 100%;

  .menu-item {
    display: flex;
    align-items: center;
    padding: 0 18px;
    font-size: 14px;
    color: var(--snail-text-secondary);
    text-decoration: none;
    border-bottom: 2px solid transparent;
    transition: color 0.2s;

    &:hover,
    &.active {
      color: var(--snail-text-primary);
    }

    &.active {
      border-bottom-color: var(--snail-accent);
    }
  }
}

.user-area {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 13px;

  .tenant {
    padding: 2px 10px;
    color: var(--snail-accent);
    border: 1px solid var(--snail-accent-dim);
    border-radius: 999px;
  }

  .username {
    color: var(--snail-text-primary);
  }
}
</style>
