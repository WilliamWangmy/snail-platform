<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/AppIcon.vue';
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue';
import ProfileEditDialog from '@/components/ProfileEditDialog.vue';
import SidebarMenu from '@/components/SidebarMenu.vue';
import ThemePicker from '@/components/ThemePicker.vue';
import { fetchUnreadCount, pageInbox, type NotifyInboxItem } from '@/api/notify';
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

/** 顶栏右侧状态区与流程端同构:今天(含星期)、环境徽标、账号头像缩写。 */
const today = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short'
})
  .format(new Date())
  .replace(/\//g, '.');

const env = import.meta.env.PROD ? 'PROD' : 'DEV';

/** 多词名取词首双字母,单词名取前两字,与流程端 avatarText 同规则。 */
const avatarText = computed(() => {
  const name = (auth.user?.nickname ?? auth.user?.username ?? '').trim();
  if (!name) return 'SA';
  const parts = name.split(/\s+/);
  if (parts.length > 1) return `${parts[0]?.[0] ?? ''}${parts[1]?.[0] ?? ''}`.toUpperCase();
  return name.slice(0, 2).toUpperCase();
});

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

async function signOut(): Promise<void> {
  await auth.signOutEverywhere();
  resetMenuRoutes(router);
  void router.push('/login');
}

/** 独立端地址:三端共用一套会话,跳转一律带一次性 ssotoken(大屏收下后可再交接);均新开页签。 */
const SCREEN_URL = import.meta.env.VITE_SCREEN_URL ?? 'http://localhost:8001/';
const FLOW_URL = import.meta.env.VITE_FLOW_URL ?? 'http://localhost:8002/';

const systems = [
  { key: 'screen', name: '数据大屏', desc: '三维态势可视化', icon: 'chart', url: SCREEN_URL },
  { key: 'flow', name: '流程平台', desc: '流程控制台', icon: 'flow', url: FLOW_URL },
];

function openStandalone(url: string): void {
  window.open(`${url}${url.includes('?') ? '&' : '?'}ssotoken=${encodeURIComponent(auth.token)}`, '_blank', 'noopener');
}

/** 顶栏系统切换入口,与流程端布局一致。 */
function handleSystemCommand(command: string | number | object): void {
  const target = systems.find((item) => item.key === command);
  if (target) openStandalone(target.url);
}

/** 账号菜单三项:个人信息/修改密码开弹窗,退出登录直接走人 */
const profileVisible = ref(false);
const passwordVisible = ref(false);

function handleUserCommand(command: string | number | object): void {
  if (command === 'logout') void signOut();
  else if (command === 'profile') profileVisible.value = true;
  else if (command === 'password') passwordVisible.value = true;
}

/* ------------------------- 通知:30s 轮询未读数,面板展开时拉最新未读 ------------------------- */

const unread = ref(0);
const latestUnread = ref<NotifyInboxItem[]>([]);
let unreadTimer: number | undefined;

async function pollUnread(): Promise<void> {
  try {
    unread.value = await fetchUnreadCount();
  } catch {
    // 轮询失败静默:通知角标是辅助信息,不因网络抖动打断主流程
  }
}

/** 顶栏只预览最新几条,正文与清已读仍在通知中心页做。 */
async function loadLatestUnread(): Promise<void> {
  try {
    latestUnread.value = (await pageInbox({ pageNum: 1, pageSize: 6, readStatus: 0 })).rows;
  } catch {
    latestUnread.value = [];
  }
}

/** 后端送 'yyyy-MM-dd HH:mm:ss',面板窄,只留 'MM-dd HH:mm'。 */
function shortTime(value: string | null): string {
  return value ? value.slice(5, 16) : '';
}

function openInbox(): void {
  void router.push('/system/notify');
}

onMounted(() => {
  window.addEventListener('resize', handleResize);
  if (auth.has('system:notify:list')) {
    void pollUnread();
    unreadTimer = window.setInterval(() => void pollUnread(), 30_000);
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  if (unreadTimer !== undefined) window.clearInterval(unreadTimer);
});
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
          <span
            class="header-workspace"
            :title="`工作区 · ${auth.user?.tenantName || '默认租户'}`"
          >
            <span class="live-dot">ONLINE</span>
          </span>
          <span class="top-date">{{ today }}</span>
          <span class="env-pill">{{ env }}</span>
          <ElPopover
            v-if="auth.has('system:notify:list')"
            trigger="click"
            placement="bottom-end"
            :width="316"
            popper-class="msg-popper"
            @before-enter="loadLatestUnread"
          >
            <template #reference>
              <button
                class="icon-btn"
                type="button"
                title="消息"
                :aria-label="`消息，${unread} 条未读`"
              >
                <AppIcon
                  name="bell"
                  :size="16"
                />
                <span
                  v-if="unread > 0"
                  class="icon-badge"
                >{{ unread > 99 ? '99+' : unread }}</span>
              </button>
            </template>

            <div class="msg-head">
              <b>消息</b>
              <span class="msg-head-count">{{ unread > 0 ? `${unread} 条未读` : '没有未读' }}</span>
            </div>
            <ul
              v-if="latestUnread.length"
              class="msg-list"
            >
              <li
                v-for="item in latestUnread"
                :key="item.recipientId"
                class="msg-item"
              >
                <span class="msg-item-title">{{ item.title }}</span>
                <span class="msg-item-meta">{{ item.senderName || '系统' }} · {{ shortTime(item.sendTime) }}</span>
              </li>
            </ul>
            <p
              v-else
              class="msg-empty"
            >
              暂无未读消息
            </p>
            <div class="msg-foot">
              <button
                class="msg-link"
                type="button"
                @click="openInbox"
              >
                查看全部
                <AppIcon
                  name="arrow-right"
                  :size="13"
                />
              </button>
            </div>
          </ElPopover>
          <ThemePicker />
          <ElDropdown
            trigger="click"
            popper-class="system-switch-popper"
            @command="handleSystemCommand"
          >
            <button
              class="icon-btn"
              type="button"
              title="切换系统"
              aria-label="切换系统"
            >
              <AppIcon
                name="more"
                :size="16"
              />
            </button>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem
                  v-for="item in systems"
                  :key="item.key"
                  :command="item.key"
                >
                  <span class="system-item">
                    <AppIcon
                      :name="item.icon"
                      :size="14"
                    />
                    <b>{{ item.name }}</b>
                    <small>{{ item.desc }}</small>
                  </span>
                </ElDropdownItem>
              </ElDropdownMenu>
            </template>
          </ElDropdown>
          <span
            class="header-divider"
            aria-hidden="true"
          ></span>
          <ElDropdown
            trigger="click"
            popper-class="user-menu-popper"
            @command="handleUserCommand"
          >
            <span
              class="header-user"
              role="button"
              tabindex="0"
              :aria-label="`账号菜单，当前：${auth.user?.nickname ?? auth.user?.username ?? ''}`"
            >
              <span class="avatar">{{ avatarText }}</span>
              <span class="header-user-copy">
                <span class="header-user-name">{{ auth.user?.nickname ?? auth.user?.username }}</span>
                <span class="header-user-role">{{ auth.user?.tenantName ?? auth.roleNames }}</span>
              </span>
              <AppIcon
                class="user-caret"
                name="chevron-down"
                :size="12"
              />
            </span>
            <template #dropdown>
              <ElDropdownMenu>
                <ElDropdownItem command="profile">
                  <span class="user-item">
                    <AppIcon
                      name="user"
                      :size="14"
                    />
                    个人信息
                  </span>
                </ElDropdownItem>
                <ElDropdownItem command="password">
                  <span class="user-item">
                    <AppIcon
                      name="lock"
                      :size="14"
                    />
                    修改密码
                  </span>
                </ElDropdownItem>
                <ElDropdownItem
                  divided
                  command="logout"
                >
                  <span class="user-item">
                    <AppIcon
                      name="logout"
                      :size="14"
                    />
                    退出登录
                  </span>
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

    <ProfileEditDialog
      :visible="profileVisible"
      @closed="profileVisible = false"
    />
    <ChangePasswordDialog
      :visible="passwordVisible"
      @closed="passwordVisible = false"
    />
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
  gap: 10px;
  margin-left: auto;
}

/* 以下状态区/分隔线/账号块逐条对齐流程端 flow-ui.css 的顶栏规格,色值换成 admin 主题令牌。 */
.header-workspace {
  display: inline-flex;
  align-items: center;
}

.live-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--app-primary);
  font-family: var(--app-font-mono);
  font-size: 9.5px;
  letter-spacing: 0.06em;
}

.live-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--app-primary);
  box-shadow: 0 0 0 4px rgba(var(--app-primary-rgb), 0.09), 0 0 10px var(--app-primary);
  animation: beacon 2.2s infinite;
}

@keyframes beacon {
  0%,
  100% {
    opacity: 1;
    box-shadow: 0 0 0 0 rgba(var(--app-primary-rgb), 0.25);
  }

  50% {
    opacity: 0.6;
    box-shadow: 0 0 0 5px rgba(var(--app-primary-rgb), 0);
  }
}

.top-date {
  color: color-mix(in srgb, var(--app-muted) 82%, transparent);
  font-family: var(--app-font-mono);
  font-size: 10.5px;
  letter-spacing: 0.05em;
}

.env-pill {
  display: inline-flex;
  height: 24px;
  align-items: center;
  gap: 6px;
  padding: 0 9px;
  border: 1px solid var(--app-line);
  border-radius: 999px;
  background: var(--app-soft);
  color: var(--app-muted);
  font-family: var(--app-font-mono);
  font-size: 9.5px;
  letter-spacing: 0.1em;
}

.env-pill::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--app-warning);
  box-shadow: 0 0 8px rgba(var(--app-warning-rgb), 0.7);
}

.header-divider {
  width: 1px;
  height: 22px;
  background: var(--app-line);
}

.header-user {
  display: flex;
  align-items: center;
  gap: 9px;
  cursor: pointer;
  outline-offset: 4px;
}

.avatar {
  display: grid;
  width: 34px;
  height: 34px;
  flex: none;
  place-items: center;
  border-radius: 10px;
  background: var(--app-primary);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.25);
  color: var(--app-on-primary);
  font-size: 12.5px;
  font-weight: 700;
}

.header-user .avatar {
  width: 31px;
  height: 31px;
  border-radius: 9px;
  font-size: 11.5px;
}

.header-user:hover .avatar {
  box-shadow: 0 0 0 2px rgba(var(--app-primary-rgb), 0.32), inset 0 1px 0 rgba(255, 255, 255, 0.25);
}

.header-user-copy {
  min-width: 0;
}

.header-user-name {
  display: block;
  overflow: hidden;
  color: var(--app-ink);
  font-size: 12px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-user-role {
  display: block;
  margin-top: 1px;
  color: color-mix(in srgb, var(--app-muted) 82%, transparent);
  font-size: 9.5px;
}

.user-caret {
  flex: none;
  color: color-mix(in srgb, var(--app-muted) 82%, transparent);
  transition: transform 0.18s ease;
}

.header-user:hover .user-caret {
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

/* 与流程端同款收敛梯度:窄屏先让位给控件,状态芯片与账号文字依次收掉。 */
@media (max-width: 1360px) {
  .top-date {
    display: none;
  }
}

@media (max-width: 1200px) {
  .header-workspace {
    display: none;
  }
}

@media (max-width: 1080px) {
  .header-user-copy,
  .header-divider,
  .env-pill {
    display: none;
  }
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

  .page-main {
    padding: 20px 18px 40px;
  }
}

@media (max-width: 760px) {
  .topbar {
    gap: 6px;
    padding: 0 12px;
  }

  .topbar-actions {
    gap: 6px;
  }

  .topbar-breadcrumb {
    min-width: 0;
  }

  .topbar-breadcrumb :deep(.el-breadcrumb__item:first-child) {
    display: none;
  }
}
</style>
