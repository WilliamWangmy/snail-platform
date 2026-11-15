<template>
  <header class="app-header">
    <div class="app-header-inner">
      <!-- 品牌(点击回总览) -->
      <a
        class="brand"
        aria-label="Snail Monitor 监控总览"
        @click="router.push('/overview')"
      >
        <span class="brand-mark">S</span>
        <span class="brand-copy">
          <span class="brand-name">Snail<span>.</span>Monitor</span>
          <span class="brand-sub">SYSTEM CONTROL ROOM</span>
        </span>
      </a>

      <!-- 一级导航:静态路由 meta.perm 过滤(DD-13);告警项带活动告警数徽标 -->
      <nav
        class="top-nav"
        aria-label="主导航"
      >
        <RouterLink
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          ><path :d="item.icon" /></svg>
          {{ item.title }}
          <span
            v-if="item.path === '/alert' && activeAlertCount > 0"
            class="nav-badge"
          >{{ activeAlertCount }}</span>
        </RouterLink>
      </nav>

      <div class="header-tools">
        <!-- 数据源状态芯片:三态色 + 抽屉(§5.7) -->
        <button
          class="status-chip"
          :class="statusClass"
          title="数据源状态（只读）"
          @click="dsDrawer = true"
        >
          <span class="status-dot" />
          {{ statusLabel }}
        </button>

        <span class="header-divider" />

        <!-- 刷新:广播 monitor-refresh,当前页自行重拉数据 -->
        <button
          class="icon-btn"
          title="刷新"
          @click="refreshPage"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          ><path d="M23 4v6h-6M1 20v-6h6" /><path d="M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15" /></svg>
        </button>

        <!-- 主题:六套(照 theme store),菜单即点即换 -->
        <el-dropdown
          trigger="click"
          popper-class="theme-popper"
          @command="selectTheme"
        >
          <button
            class="icon-btn"
            title="主题"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            ><path d="M21 12.8A9 9 0 1 1 11.2 3 7 7 0 0 0 21 12.8z" /></svg>
          </button>
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

        <!-- 告警铃铛 -->
        <button
          class="icon-btn"
          title="告警中心"
          @click="router.push('/alert')"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
          ><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.7 21a2 2 0 0 1-3.4 0" /></svg>
          <span
            v-if="activeAlertCount > 0"
            class="bell-dot"
          />
        </button>

        <!-- 跨端切换 + 退出:按 tenantFeatures 过滤,宁少显示不越权 -->
        <div class="sys-wrap">
          <button
            class="icon-btn"
            title="切换系统"
            @click.stop="sysMenuOpen = !sysMenuOpen"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
            ><rect
              x="3"
              y="3"
              width="7"
              height="7"
              rx="1"
            /><rect
              x="14"
              y="3"
              width="7"
              height="7"
              rx="1"
            /><rect
              x="3"
              y="14"
              width="7"
              height="7"
              rx="1"
            /><rect
              x="14"
              y="14"
              width="7"
              height="7"
              rx="1"
            /></svg>
          </button>
          <div
            class="sys-menu"
            :class="{ open: sysMenuOpen }"
          >
            <button
              v-if="canOpen('admin')"
              class="sys-item"
              @click="gotoApp('admin')"
            >管理中心<span>:8000 · 已开通（tenantFeatures.adminEnabled）</span></button>
            <button
              v-if="canOpen('screen')"
              class="sys-item"
              @click="gotoApp('screen')"
            >数据大屏<span>:8001 · 已开通（tenantFeatures.dashboardEnabled）</span></button>
            <button
              class="sys-item"
              @click="logout"
            >退出登录<span>{{ accountLabel }}</span></button>
          </div>
        </div>

        <div
          class="avatar"
          :title="accountLabel"
        >{{ avatarText }}</div>
      </div>
    </div>

    <!-- 数据源抽屉:metrics/logs/arthas/links 四段(§5.7) -->
    <el-drawer
      v-model="dsDrawer"
      title="数据源状态"
      size="460px"
    >
      <template v-if="status">
        <div class="card-title">指标源<span class="sub mono">metrics.source</span><span class="right"><span
          class="chip"
          :class="status.metrics.reachable ? 'chip-success' : 'chip-warning'"
        >{{ status.metrics.reachable ? '可达' : '不可达' }}</span></span></div>
        <div class="kv">
          <div>配置值 / 生效值</div><div class="mono">{{ status.metrics.configured }} → {{ status.metrics.active }}</div>
          <div>查询延迟</div><div>{{ status.metrics.latencyMs === null ? '内置采集，无外部调用' : `${status.metrics.latencyMs} ms` }}</div>
          <div>映射键</div><div class="mono">{{ status.metrics.mappedKeys }} / {{ status.metrics.totalKeys }}</div>
          <div>base-url</div><div class="mono">{{ status.metrics.baseUrl || '未配置' }}</div>
          <div>最近错误</div><div>{{ status.metrics.lastError || '无' }}</div>
        </div>
        <div
          class="card-title"
          style="margin-top: 18px"
        >日志源<span class="sub mono">logs.source</span><span class="right"><span
          class="chip"
          :class="status.logs.reachable ? 'chip-success' : 'chip-warning'"
        >{{ status.logs.reachable ? '可达' : '不可达' }}</span></span></div>
        <div class="kv">
          <div>配置值 / 生效值</div><div class="mono">{{ status.logs.configured }} → {{ status.logs.active }}</div>
          <div>查询延迟</div><div>{{ status.logs.latencyMs === null ? '本地文件，无外部调用' : `${status.logs.latencyMs} ms` }}</div>
          <div>选择器 / 文件</div><div class="mono">{{ status.logs.selector || status.logs.baseUrl || 'logs/snail-platform.log' }}</div>
          <div>最近错误</div><div>{{ status.logs.lastError || '无' }}</div>
        </div>
        <div
          class="card-title"
          style="margin-top: 18px"
        >外部栈入口<span class="sub">Grafana 外链；Arthas 在诊断页内嵌官方控制台</span></div>
        <div class="kv">
          <div>Grafana</div><div><a
            v-if="status.links.grafanaUrl"
            class="link"
            :href="status.links.grafanaUrl"
            target="_blank"
            rel="noopener"
          >{{ status.links.grafanaUrl }}</a><span
            v-else
            class="muted"
          >未配置</span></div>
          <div>Arthas 在线诊断</div><div>
            <template v-if="status.arthas.enabled">
              <a
                class="link"
                @click="gotoDiag"
              >{{ status.arthas.agentCount }} 个 agent · 跳诊断页查看</a>
              <span
                v-if="status.arthas.baseUrl"
                class="muted mono"
                style="font-size: 11px"
              >（{{ status.arthas.baseUrl }}）</span>
            </template>
            <span
              v-else
              class="muted"
            >未启用（snail.monitor.arthas.enabled=false）</span>
          </div>
        </div>
        <p class="muted" style="font-size: 11.5px; line-height: 1.6">
          状态接口 30s 缓存；配置只读，修改 metrics.source / logs.source 需改 monitor.yml 并重启；凭据仅走环境变量，本接口不回显。
        </p>
      </template>
      <div
        v-else
        class="empty"
      >加载中…</div>
    </el-drawer>
  </header>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { RouterLink, useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useThemeStore } from '@/stores/theme';
import { fetchDatasourceStatus, type DataSourceStatus } from '@/api/monitor/datasource';
import { fetchActiveAlerts } from '@/api/monitor/alert';
import { openCrossApp } from '@/utils/links';
import { routes } from '@/router/routes';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const themeStore = useThemeStore();

/** 导航图标(照原型 SVG path);告警项由模板追加活动告警数徽标 */
const NAV_ICONS: Record<string, string> = {
  overview: 'M3 3h7v9H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 16h7v5H3z',
  alert: 'M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9 M13.7 21a2 2 0 0 1-3.4 0',
  log: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8 M10 9H8',
  diag: 'M22 12h-4l-3 9L9 3l-3 9H2',
};

/** 一级导航:根 Layout children 过滤 meta.title && !meta.perm || has(perm);宁少显示不越权。 */
const navItems = computed(() => {
  const layoutRoute = routes.find((item) => item.path === '/');
  const children = (layoutRoute?.children ?? []) as { path: string; meta?: { title?: string; perm?: string } }[];
  return children
    .filter((child) => child.meta?.title && (!child.meta?.perm || auth.has(child.meta.perm)))
    .map((child) => ({ path: `/${child.path}`, title: child.meta!.title!, icon: NAV_ICONS[child.path] ?? NAV_ICONS.overview! }));
});

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(`${path}/`);
}

// ---------- 数据源状态芯片(30s 轮询,组件卸载即停) ----------

const status = ref<DataSourceStatus | null>(null);
const dsDrawer = ref(false);
const activeAlertCount = ref(0);
let timer: number | undefined;

const statusLabel = computed(() => {
  if (!status.value) return '数据源 …';
  const metricsOk = status.value.metrics.active === 'builtin' || status.value.metrics.reachable;
  const logsOk = status.value.logs.configured === 'file' || status.value.logs.reachable;
  return `数据源 ${status.value.metrics.active} / ${status.value.logs.active}${metricsOk && logsOk ? '' : ' · 降级'}`;
});

const statusClass = computed(() => {
  if (!status.value) return 'unknown';
  const metricsOk = status.value.metrics.active === 'builtin' || status.value.metrics.reachable;
  const logsOk = status.value.logs.configured === 'file' || status.value.logs.reachable;
  return metricsOk && logsOk ? '' : 'degraded';
});

async function pollStatus(): Promise<void> {
  try {
    status.value = await fetchDatasourceStatus();
  } catch {
    /* 401 由拦截器统一跳登录;这里静默保持上次快照 */
  }
  try {
    const alerts = await fetchActiveAlerts();
    activeAlertCount.value = alerts.filter((a) => a.status !== 'resolved').length;
  } catch {
    /* 徽标失败静默 */
  }
}

function gotoDiag(): void {
  dsDrawer.value = false;
  void router.push('/diag');
}

// ---------- 工具区 ----------

function selectTheme(id: string): void {
  themeStore.select(id);
}

/** 刷新:广播事件由当前页监听重拉,不整页 reload。 */
function refreshPage(): void {
  window.dispatchEvent(new CustomEvent('monitor-refresh'));
}

const sysMenuOpen = ref(false);

function onDocClick(): void {
  sysMenuOpen.value = false;
}

const accountLabel = computed(() => {
  const user = auth.user;
  if (!user) return '未登录';
  return `${user.nickname || user.username} · ${user.tenantName}`;
});

const avatarText = computed(() => {
  const name = auth.user?.nickname || auth.user?.username || '';
  return name.slice(0, 2).toUpperCase() || '未';
});

/** 跨端入口按 tenantFeatures 过滤:字段缺失一律隐藏(照 workflow showSystemSwitch 口径)。 */
function canOpen(key: 'admin' | 'screen'): boolean {
  const features = auth.user?.tenantFeatures;
  if (!features) return false;
  if (key === 'admin') return features.adminEnabled === true;
  return features.dashboardEnabled === true;
}

function gotoApp(key: 'admin' | 'screen'): void {
  sysMenuOpen.value = false;
  if (auth.token) openCrossApp(key, auth.token);
}

function logout(): void {
  sysMenuOpen.value = false;
  void auth.signOutEverywhere().then(() => router.push('/login'));
}

onMounted(() => {
  void pollStatus();
  timer = window.setInterval(() => void pollStatus(), 30000);
  document.addEventListener('click', onDocClick);
});

onUnmounted(() => {
  if (timer) window.clearInterval(timer);
  document.removeEventListener('click', onDocClick);
});
</script>

<style>
.theme-active {
  color: var(--app-primary);
  font-weight: 600;
}
</style>
