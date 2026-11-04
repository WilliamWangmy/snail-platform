<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox } from 'element-plus';

import AppIcon from '@/components/AppIcon.vue';
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue';
import ProfileEditDialog from '@/components/ProfileEditDialog.vue';
import ThemePicker from '@/components/ThemePicker.vue';
import { useAuthStore } from '@/stores/auth';
import { openCrossApp, type SystemKey } from '@/utils/links';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const profileVisible = ref(false);
const passwordVisible = ref(false);

/* ------------------------- 消息角标:30s 轮询未读数 ------------------------- */

const unread = ref(0);
const latestUnread = ref<NotifyInboxItem[]>([]);
/** 收件箱列表端点要 system:notify:list,审批人角色未必带它;无权限时面板只报未读数,不给死链。 */
const canReadInbox = computed(() => auth.has('system:notify:list'));
let unreadTimer: number | undefined;

async function pollUnread(): Promise<void> {
  try {
    unread.value = await fetchUnreadCount();
  } catch {
    // 轮询失败静默:角标是辅助信息,不因网络抖动打断主流程
  }
}

async function loadLatestUnread(): Promise<void> {
  if (!canReadInbox.value) return;
  try {
    latestUnread.value = (await pageLatestUnread()).rows;
  } catch {
    latestUnread.value = [];
  }
}

/** 后端送 'yyyy-MM-dd HH:mm:ss',面板窄,只留 'MM-dd HH:mm'。 */
function shortTime(value: string | null): string {
  return value ? value.slice(5, 16) : '';
}

function openInbox(): void {
  openCrossApp('admin', auth.token, '/system/notify');
}

onMounted(() => {
  void pollUnread();
  unreadTimer = window.setInterval(() => void pollUnread(), 30_000);
});

onBeforeUnmount(() => {
  if (unreadTimer !== undefined) window.clearInterval(unreadTimer);
});

function onUserCommand(command: string | number | object): void {
  if (command === 'profile') profileVisible.value = true;
  else if (command === 'password') passwordVisible.value = true;
  else if (command === 'logout') void handleLogout();
}

function onSwitchSystem(command: string | number | object): void {
  openCrossApp(command as SystemKey, auth.token);
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
        <ElPopover
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
              <AppIcon name="bell" />
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
            {{ canReadInbox ? '暂无未读消息' : `有 ${unread} 条未读，正文需在管理端通知中心查看` }}
          </p>
          <div
            v-if="canReadInbox"
            class="msg-foot"
          >
            <button
              class="text-btn"
              type="button"
              @click="openInbox"
            >
              查看全部
              <AppIcon
                name="external-link"
                :size="13"
              />
            </button>
          </div>
        </ElPopover>
        <ThemePicker />
        <el-dropdown
          trigger="click"
          popper-class="system-switch-popper"
          @command="onSwitchSystem"
        >
          <button
            class="icon-btn"
            type="button"
            title="切换系统"
            aria-label="切换系统"
          >
            <AppIcon name="more" />
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="admin">
                <span class="system-item">
                  <AppIcon name="shield" />
                  <span class="system-item-name">管理端</span>
                  <span class="system-item-desc">多租户运营控制台</span>
                </span>
              </el-dropdown-item>
              <el-dropdown-item command="screen">
                <span class="system-item">
                  <AppIcon name="activity" />
                  <span class="system-item-name">数据大屏</span>
                  <span class="system-item-desc">三维态势可视化</span>
                </span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <span
          class="header-divider"
          aria-hidden="true"
        ></span>
        <el-dropdown
          trigger="click"
          popper-class="user-menu-popper"
          @command="onUserCommand"
        >
          <span
            class="header-user"
            role="button"
            tabindex="0"
            :aria-label="`账号菜单，当前：${auth.displayName}`"
          >
            <span class="avatar">{{ avatarText }}</span>
            <span class="header-user-copy">
              <span class="header-user-name">{{ auth.displayName }}</span>
              <span class="header-user-role">{{ auth.tenantName || '流程管理员' }}</span>
            </span>
            <AppIcon
              class="user-caret"
              name="chevron-down"
              :size="12"
            />
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
                <span class="user-item"><AppIcon name="user" />个人信息</span>
              </el-dropdown-item>
              <el-dropdown-item command="password">
                <span class="user-item"><AppIcon name="lock" />修改密码</span>
              </el-dropdown-item>
              <el-dropdown-item
                divided
                command="logout"
              >
                <span class="user-item"><AppIcon name="log-out" />退出登录</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <ProfileEditDialog
        :visible="profileVisible"
        @closed="profileVisible = false"
      />
      <ChangePasswordDialog
        :visible="passwordVisible"
        @closed="passwordVisible = false"
      />
    </div>
  </header>
</template>
