<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import SidebarMenu from '@/components/SidebarMenu.vue';
import ThemePicker from '@/components/ThemePicker.vue';
import { useAuthStore } from '@/stores/auth';
import { resetMenuRoutes } from '@/router/menuRoutes';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const collapsed = ref(false);
const mobile = ref(window.innerWidth <= 1024);
const mobileOpen = ref(false);

const sidebarWidth = computed(() => (collapsed.value && !mobile.value ? '82px' : '248px'));
const breadcrumb = computed(() => route.meta.breadcrumb ?? [route.meta.title ?? '']);
const initial = computed(() => (auth.user?.nickname ?? auth.user?.username ?? 'S').slice(0, 1));

function handleResize(): void {
  const value = window.innerWidth <= 1024;
  if (value !== mobile.value) {
    mobile.value = value;
    mobileOpen.value = false;
    if (value) collapsed.value = false;
  }
}

function toggleSidebar(): void {
  if (mobile.value) mobileOpen.value = !mobileOpen.value;
  else collapsed.value = !collapsed.value;
}

/** index 可能是外链 URL,也可能已是站内路由。 */
function handleSelect(index: string): void {
  if (/^https?:\/\//.test(index)) window.open(index, '_blank', 'noopener');
  else if (index !== route.path) void router.push(index);
  mobileOpen.value = false;
}

function signOut(): void {
  auth.signOut();
  resetMenuRoutes(router);
  void router.push('/login');
}

function handleUserCommand(command: string | number | object): void {
  if (command === 'logout') signOut();
}

onMounted(() => window.addEventListener('resize', handleResize));
onBeforeUnmount(() => window.removeEventListener('resize', handleResize));
</script>

<template>
  <ElContainer class="app-shell">
    <div
      v-if="mobile && mobileOpen"
      class="mobile-mask"
      @click="mobileOpen = false"
    ></div>

    <ElAside
      :width="sidebarWidth"
      class="app-sidebar"
      :class="{ collapsed, 'mobile-open': mobileOpen }"
    >
      <div class="sidebar-brand">
        <span class="brand-mark"><AppIcon
          name="shell"
          :size="24"
        /></span>
        <div
          v-if="!collapsed || mobile"
          class="sidebar-brand-text"
        >
          <strong>Snail Admin</strong>
          <span>OPERATIONS CONSOLE</span>
        </div>
      </div>

      <ElMenu
        :default-active="route.path"
        :collapse="collapsed && !mobile"
        :collapse-transition="false"
        unique-opened
        class="side-menu"
        @select="handleSelect"
      >
        <ElMenuItem index="/home">
          <AppIcon
            name="grid"
            :size="18"
          />
          <span>工作台</span>
        </ElMenuItem>
        <SidebarMenu :nodes="auth.menus" />
      </ElMenu>

      <div class="sidebar-foot">
        <div class="side-user">
          <ElAvatar
            :size="34"
            :style="{ background: 'var(--app-primary)', color: 'var(--app-on-primary)' }"
          >
            {{ initial }}
          </ElAvatar>
          <div
            v-if="!collapsed || mobile"
            class="side-user-text"
          >
            <strong>{{ auth.user?.nickname ?? auth.user?.username }}</strong>
            <span>{{ auth.roleNames }}</span>
          </div>
          <ElButton
            v-if="!collapsed || mobile"
            text
            circle
            class="side-logout"
            aria-label="退出登录"
            @click="signOut"
          >
            <AppIcon
              name="logout"
              :size="16"
            />
          </ElButton>
        </div>
      </div>
    </ElAside>

    <ElContainer class="workspace-main">
      <ElHeader
        height="68px"
        class="topbar"
      >
        <ElButton
          text
          circle
          class="topbar-icon"
          aria-label="切换菜单"
          @click="toggleSidebar"
        >
          <AppIcon
            name="menu"
            :size="19"
          />
        </ElButton>
        <ElBreadcrumb
          separator="/"
          class="topbar-breadcrumb"
        >
          <ElBreadcrumbItem
            v-for="(item, position) in breadcrumb"
            :key="`${item}-${position}`"
          >
            {{ item }}
          </ElBreadcrumbItem>
        </ElBreadcrumb>
        <div class="topbar-actions">
          <ThemePicker />
          <div class="topbar-divider"></div>
          <ElDropdown
            trigger="click"
            @command="handleUserCommand"
          >
            <span class="avatar-trigger">
              <ElAvatar
                :size="34"
                :style="{ background: 'var(--app-primary)', color: 'var(--app-on-primary)' }"
              >
                {{ initial }}
              </ElAvatar>
              <span class="avatar-text">
                <b>{{ auth.user?.nickname ?? auth.user?.username }}</b>
                <small>{{ auth.user?.tenantName ?? auth.roleNames }}</small>
              </span>
              <AppIcon
                name="chevron-down"
                :size="12"
              />
            </span>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem command="logout">
                  退出登录
                </ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
        </div>
      </ElHeader>

      <ElMain class="page-main">
        <div class="page-wrap">
          <RouterView v-slot="{ Component }">
            <Transition
              name="page"
              mode="out-in"
            >
              <component :is="Component" />
            </Transition>
          </RouterView>
        </div>
      </ElMain>
    </ElContainer>
  </ElContainer>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--app-bg);
}

.mobile-mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  background: rgba(4, 25, 22, 0.45);
  backdrop-filter: blur(3px);
}

.app-sidebar {
  display: flex;
  flex-direction: column;
  padding: 20px 12px 14px;
  transition: width 0.24s ease;
  background:
    radial-gradient(420px 280px at 25% 0%, rgba(var(--app-primary-rgb), 0.13), transparent 65%),
    linear-gradient(180deg, var(--app-sidebar), var(--app-sidebar-2));
  overflow: hidden;
}

.app-sidebar.collapsed {
  padding-left: 8px;
  padding-right: 8px;
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 42px;
  padding: 0 8px 22px;
}

.app-sidebar.collapsed .sidebar-brand {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}

.sidebar-brand-text strong {
  display: block;
  color: #f0fffc;
  font-size: 15px;
  letter-spacing: -0.02em;
}

.sidebar-brand-text span {
  display: block;
  margin-top: 2px;
  color: var(--app-sidebar-muted);
  font-size: 8px;
  letter-spacing: 0.14em;
}

.side-menu {
  flex: 1;
  border-right: 0 !important;
  background: transparent !important;
  overflow-x: hidden;
  overflow-y: auto;
}

.side-menu :deep(.el-menu-item),
.side-menu :deep(.el-sub-menu__title) {
  position: relative;
  height: 44px;
  margin: 2px 0;
  border-radius: 10px;
  color: var(--app-sidebar-text);
  font-size: 13px;
}

.side-menu :deep(.el-menu-item:hover),
.side-menu :deep(.el-sub-menu__title:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.06) !important;
}

.side-menu :deep(.el-menu-item.is-active) {
  color: #fff;
  background: linear-gradient(
    90deg,
    rgba(var(--app-primary-rgb), 0.23),
    rgba(var(--app-primary-rgb), 0.04)
  ) !important;
  box-shadow: inset 0 0 0 1px rgba(var(--app-primary-rgb), 0.16);
}

.side-menu > .el-menu-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 11px;
  bottom: 11px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--app-primary);
  box-shadow: 0 0 10px var(--app-primary);
}

.side-menu :deep(.el-sub-menu .el-menu) {
  background: transparent !important;
}

.side-menu :deep(.el-sub-menu .el-menu-item) {
  padding-left: 52px !important;
  font-size: 12px;
}

.side-menu :deep(.el-sub-menu .el-menu-item)::after {
  content: '';
  position: absolute;
  left: 31px;
  top: 50%;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--app-sidebar-muted);
  transform: translateY(-50%);
}

.side-menu :deep(.el-sub-menu .el-menu-item.is-active)::after {
  width: 6px;
  height: 6px;
  background: var(--app-primary);
  box-shadow: 0 0 7px var(--app-primary);
}

.side-menu :deep(.app-icon) {
  margin-right: 10px;
}

.app-sidebar.collapsed :deep(.side-menu .app-icon) {
  margin-right: 0;
}

.side-menu :deep(.el-sub-menu__icon-arrow) {
  color: var(--app-sidebar-muted);
}

.sidebar-foot {
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.side-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 5px 0;
}

.app-sidebar.collapsed .side-user {
  justify-content: center;
  padding-left: 0;
}

.side-user-text strong {
  display: block;
  color: #f0fffc;
  font-size: 12px;
}

.side-user-text span {
  display: block;
  margin-top: 2px;
  color: var(--app-sidebar-muted);
  font-size: 9px;
}

.side-logout {
  margin-left: auto;
  color: var(--app-sidebar-muted) !important;
}

.workspace-main {
  min-width: 0;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 28px;
  background: rgba(var(--app-card-rgb), 0.92);
  border-bottom: 1px solid var(--app-line);
  backdrop-filter: blur(14px);
}

.topbar-icon {
  color: var(--app-muted) !important;
}

.topbar-icon:hover {
  color: var(--app-primary) !important;
  background: var(--app-soft) !important;
}

.topbar-breadcrumb {
  min-width: 150px;
}

.topbar-breadcrumb :deep(.el-breadcrumb__inner) {
  color: var(--app-muted);
  font-size: 12px;
}

.topbar-breadcrumb :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--app-ink);
  font-weight: 600;
}

.topbar-actions {
  display: flex;
  align-items: center;
  gap: 3px;
  margin-left: auto;
}

.topbar-divider {
  width: 1px;
  height: 24px;
  margin: 0 5px;
  background: var(--app-line);
}

.avatar-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border-radius: 10px;
  cursor: pointer;
  outline: none;
}

.avatar-trigger:hover {
  background: var(--app-soft);
}

.avatar-text b {
  display: block;
  color: var(--app-ink);
  font-size: 11.5px;
}

.avatar-text small {
  display: block;
  color: var(--app-muted);
  font-size: 9px;
}

.avatar-trigger > .app-icon {
  color: var(--app-muted);
}

.page-main {
  padding: 26px 30px 50px;
  background: var(--app-bg);
}

.page-enter-active,
.page-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.page-enter-from,
.page-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media (max-width: 1024px) {
  .app-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    width: 248px !important;
    transform: translateX(-105%);
    transition: transform 0.25s ease;
    z-index: 90;
  }

  .app-sidebar.collapsed {
    width: 248px !important;
  }

  .app-sidebar.mobile-open {
    transform: translateX(0);
    box-shadow: 20px 0 50px rgba(0, 0, 0, 0.26);
  }

  .avatar-text {
    display: none;
  }

  .page-main {
    padding: 20px 18px 40px;
  }
}

@media (max-width: 760px) {
  .topbar {
    gap: 6px;
    padding: 0 12px;
  }

  .topbar-breadcrumb {
    min-width: 0;
  }

  .topbar-breadcrumb :deep(.el-breadcrumb__item:first-child) {
    display: none;
  }
}
</style>
