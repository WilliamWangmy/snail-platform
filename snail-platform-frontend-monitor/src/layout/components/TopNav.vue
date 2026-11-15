<template>
  <header class="topnav">
    <div class="left">
      <span class="brand">Snail 系统监控</span>
      <nav class="nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
        >
          {{ item.title }}
        </RouterLink>
      </nav>
    </div>

    <div class="right">
      <!-- 数据源状态芯片:三态色 + 抽屉(§5.7) -->
      <button class="chip" :class="statusClass" @click="drawerVisible = true">
        <span class="dot" />
        {{ statusLabel }}
      </button>

      <el-tooltip content="刷新当前页" placement="bottom">
        <button class="icon-btn" @click="reloadPage">⟳</button>
      </el-tooltip>

      <el-dropdown @command="selectTheme">
        <button class="icon-btn" title="主题">🎨</button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item
              v-for="theme in themeStore.themes"
              :key="theme.id"
              :command="theme.id"
              :class="{ 'theme-active': theme.id === themeStore.currentId }"
            >
              {{ theme.name }}（{{ theme.desc }}）
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <el-tooltip content="告警中心" placement="bottom">
        <button class="icon-btn" @click="router.push('/alert')">🔔</button>
      </el-tooltip>

      <el-dropdown @command="onAccountCommand">
        <span class="account">{{ shortName }}</span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="canOpen('admin')" command="admin">前往管理端</el-dropdown-item>
            <el-dropdown-item v-if="canOpen('screen')" command="screen">前往数据大屏</el-dropdown-item>
            <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>

    <!-- 数据源抽屉:metrics/logs/arthas/links 四行(§5.7) -->
    <el-drawer v-model="drawerVisible" title="数据源状态" size="420px">
      <div v-if="status" class="ds-list">
        <div class="ds-row">
          <div class="ds-title">指标源 {{ status.metrics.configured }}</div>
          <div class="ds-desc">
            active={{ status.metrics.active }} · 可达={{ status.metrics.reachable ? '是' : '否' }}
            <template v-if="status.metrics.baseUrl"> · {{ status.metrics.baseUrl }}</template>
            <template v-if="status.metrics.mappedKeys"> · 映射 {{ status.metrics.mappedKeys }}/{{ status.metrics.totalKeys }} 键</template>
          </div>
          <div v-if="status.metrics.lastError" class="ds-error">{{ status.metrics.lastError }}</div>
        </div>
        <div class="ds-row">
          <div class="ds-title">日志源 {{ status.logs.configured }}</div>
          <div class="ds-desc">
            active={{ status.logs.active }} · 可达={{ status.logs.reachable ? '是' : '否' }}
            <template v-if="status.logs.baseUrl"> · {{ status.logs.baseUrl }}</template>
          </div>
          <div v-if="status.logs.lastError" class="ds-error">{{ status.logs.lastError }}</div>
        </div>
        <div class="ds-row">
          <div class="ds-title">Arthas</div>
          <div class="ds-desc" v-if="status.arthas.enabled">
            可达={{ status.arthas.reachable ? '是' : '否' }} · agent {{ status.arthas.agentCount }} 个
            <template v-if="status.arthas.baseUrl"> · {{ status.arthas.baseUrl }}</template>
          </div>
          <div class="ds-desc" v-else>未启用</div>
        </div>
        <div class="ds-row" v-if="status.links.grafanaUrl">
          <div class="ds-title">Grafana</div>
          <div class="ds-desc">
            <a :href="status.links.grafanaUrl" target="_blank" rel="noopener">{{ status.links.grafanaUrl }}</a>
          </div>
        </div>
      </div>
    </el-drawer>
  </header>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useThemeStore } from '@/stores/theme';
import { fetchDatasourceStatus, type DataSourceStatus } from '@/api/monitor/datasource';
import { openCrossApp } from '@/utils/links';
import { routes } from '@/router/routes';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const themeStore = useThemeStore();

/** 一级导航:根 Layout children 过滤 meta.title && !meta.perm || has(perm);宁少显示不越权。 */
const navItems = computed(() => {
  const layoutRoute = routes.find((item) => item.path === '/');
  const children = (layoutRoute?.children ?? []) as { path: string; meta?: { title?: string; perm?: string } }[];
  return children
    .filter((child) => child.meta?.title && (!child.meta?.perm || auth.has(child.meta.perm)))
    .map((child) => ({ path: `/${child.path}`, title: child.meta!.title! }));
});

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(`${path}/`);
}

// ---------- 数据源状态芯片(30s 轮询,组件卸载即停) ----------

const status = ref<DataSourceStatus | null>(null);
const drawerVisible = ref(false);
let timer: number | undefined;

const statusLabel = computed(() => {
  if (!status.value) return '状态未知';
  const metricsOk = status.value.metrics.active === 'builtin' || status.value.metrics.reachable;
  const logsOk = status.value.logs.configured === 'file' || status.value.logs.reachable;
  if (metricsOk && logsOk) return '数据源正常';
  if (status.value.logs.active === 'unavailable' || !status.value.metrics.reachable) return '数据源降级';
  return '数据源正常';
});

const statusClass = computed(() => {
  if (!status.value) return 'unknown';
  const metricsOk = status.value.metrics.active === 'builtin' || status.value.metrics.reachable;
  const logsOk = status.value.logs.configured === 'file' || status.value.logs.reachable;
  return metricsOk && logsOk ? 'normal' : 'degraded';
});

async function loadStatus() {
  try {
    status.value = await fetchDatasourceStatus();
  } catch {
    /* 401 由拦截器统一跳登录;这里静默保持上次快照 */
  }
}

onMounted(() => {
  loadStatus();
  timer = window.setInterval(loadStatus, 30000);
});

onUnmounted(() => {
  if (timer) window.clearInterval(timer);
});

// ---------- 主题与账号 ----------

function selectTheme(id: string) {
  themeStore.select(id);
}

function reloadPage() {
  router.go(0);
}

const shortName = computed(() => {
  const nickname = auth.user?.nickname || auth.user?.username || '';
  return nickname.length > 6 ? nickname.slice(0, 6) : nickname || '未登录';
});

/** 跨端入口按 tenantFeatures 过滤:字段缺失一律隐藏(照 workflow showSystemSwitch 口径)。 */
function canOpen(key: 'admin' | 'screen'): boolean {
  const features = auth.user?.tenantFeatures;
  if (!features) return false;
  if (key === 'admin') return features.adminEnabled === true;
  return features.dashboardEnabled === true;
}

function onAccountCommand(command: string) {
  if (command === 'logout') {
    auth.signOutEverywhere().then(() => router.push('/login'));
    return;
  }
  if (auth.token) openCrossApp(command as 'admin' | 'screen', auth.token);
}
</script>

<script lang="ts">
import { onUnmounted } from 'vue';
export default { name: 'TopNav' };
</script>

<style scoped>
.topnav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  background: var(--app-card);
  border-bottom: 1px solid var(--app-line);
  position: sticky;
  top: 0;
  z-index: 10;
}
.left {
  display: flex;
  align-items: center;
  gap: 24px;
}
.brand {
  font-weight: 700;
  font-size: 15px;
  color: var(--app-primary);
}
.nav {
  display: flex;
  gap: 4px;
}
.nav-item {
  padding: 6px 14px;
  border-radius: 8px;
  color: var(--app-muted);
  text-decoration: none;
  font-size: 14px;
}
.nav-item.active {
  color: var(--app-primary);
  background: rgba(var(--app-primary-rgb), .12);
  font-weight: 600;
}
.right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--app-line);
  background: var(--app-soft);
  color: var(--app-muted);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
}
.chip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-success);
}
.chip.degraded .dot {
  background: var(--app-warning);
}
.chip.unknown .dot {
  background: var(--app-muted);
}
.icon-btn {
  border: none;
  background: transparent;
  font-size: 16px;
  cursor: pointer;
  color: var(--app-muted);
  padding: 4px;
}
.account {
  cursor: pointer;
  font-size: 13px;
  color: var(--app-ink);
}
.ds-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.ds-row {
  border: 1px solid var(--app-line);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--app-soft);
}
.ds-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.ds-desc {
  font-size: 12px;
  color: var(--app-muted);
  word-break: break-all;
}
.ds-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-danger);
}
.theme-active {
  color: var(--app-primary);
  font-weight: 600;
}
</style>
