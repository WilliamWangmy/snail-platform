<template>
  <div>
    <!-- 状态卡(照原型:状态芯片 + 说明 + 元信息 + 刷新) -->
    <div
      class="card"
      style="margin-bottom: 14px"
    >
      <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
        <span
          class="chip"
          :class="statusChipClass"
        >{{ statusChipText }}</span>
        <span style="font-size: 13px; font-weight: 600">{{ statusText }}</span>
        <span
          class="muted mono"
          style="font-size: 11px"
        >{{ statusMeta }}</span>
        <span style="flex: 1" />
        <el-button
          size="small"
          :loading="loading"
          @click="loadStatus"
        >刷新状态</el-button>
      </div>
      <div
        v-if="status && !status.enabled"
        class="empty"
        style="margin-top: 12px"
      >
        未启用：<span class="mono">snail.monitor.arthas.enabled=false</span> 或 <span class="mono">base-url</span> 为空 —— 真实页面不渲染本 tab。
      </div>
    </div>

    <!-- 已接入 Agent(照原型表) -->
    <div
      v-if="status?.reachable && status.agents.length"
      class="card"
      style="margin-bottom: 14px"
    >
      <div class="card-title">
        已接入 Agent
        <span class="sub">GET /monitor/arthas/status · 30s 缓存 · 凭据不回吐</span>
      </div>
      <el-table
        :data="status.agents"
        size="default"
      >
        <el-table-column label="agentId">
          <template #default="{ row }"><span class="mono">{{ row.agentId }}</span></template>
        </el-table-column>
        <el-table-column label="主机 : 端口">
          <template #default="{ row }">{{ row.host || '未知来源' }}<template v-if="row.port">:{{ row.port }}</template></template>
        </el-table-column>
        <el-table-column label="Arthas 版本">
          <template #default="{ row }">{{ row.arthasVersion || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态">
          <template #default><span class="chip chip-success">在线</span></template>
        </el-table-column>
        <el-table-column
          label="操作"
          align="right"
          width="110"
        >
          <template #default="{ row }">
            <el-button
              size="small"
              type="primary"
              plain
              @click="openConsole(row.agentId)"
            >打开控制台</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div
      v-else-if="status?.reachable"
      class="card"
      style="margin-bottom: 14px"
    >
      <div class="empty">tunnel 已可达，当前无注册 agent —— agent 启动需加 <span class="mono">--tunnel-server ws://{host}:7777/ws --agent-id {全局唯一}</span></div>
    </div>

    <!-- 在线控制台(照原型:arthas-frame 深底框 + bar + iframe) -->
    <div
      v-if="iframeUrl"
      class="card"
      style="margin-bottom: 14px"
    >
      <div class="card-title">
        在线控制台
        <span class="sub">iframe 加载 tunnel 官方控制台 · 命令由浏览器直连隧道 WS</span>
        <span class="right">
          <el-button
            size="small"
            @click="closeConsole"
          >收起</el-button>
        </span>
      </div>
      <div class="arthas-frame">
        <div class="arthas-bar">
          <span class="dot" />
          <span
            class="mono"
            style="color: #cfe0f5"
          >Arthas Console · agentId {{ activeAgentId }}</span>
          <span
            class="mono"
            style="margin-left: auto; overflow: hidden; text-overflow: ellipsis"
          >{{ iframeUrl }}</span>
        </div>
        <iframe
          :src="iframeUrl"
          class="arthas-iframe"
          sandbox="allow-scripts allow-same-origin allow-forms allow-downloads"
          referrerpolicy="no-referrer"
          @load="frameLoaded = true"
        />
        <p
          v-if="frameBlocked"
          class="diag-sug"
          style="padding: 0 12px 10px"
        >iframe 未能加载：检查构建期 <span class="mono">VITE_MONITOR_ARTHAS_ORIGIN</span> 与 tunnel HTTPS/WSS（CSP frame-src 白名单 / 混合内容拦截）</p>
      </div>
      <p
        class="muted"
        style="font-size: 11.5px; margin-top: 10px; line-height: 1.7"
      >
        控制台用 <span class="mono">&lt;iframe&gt;</span> 加载
        <span class="mono">{tunnel}/index.html?agentId=…&amp;iframe=true&amp;port=7777</span>，控制台自行连接 WS。平台后端不参与命令执行、不落命令历史。
      </p>
    </div>

    <!-- 安全与部署前置(照原型) -->
    <div class="card">
      <div class="card-title">
        安全与部署前置
        <span class="sub">OQ-25</span>
      </div>
      <ul
        class="muted"
        style="font-size: 12px; line-height: 1.95; padding-left: 18px; margin: 0"
      >
        <li>本系统只做「壳」：不代理 Arthas 命令、不托管会话、不保存命令历史；</li>
        <li>tunnel 控制台自身<b>无鉴权</b> —— 必须内网或反代白名单，禁止直接公网暴露；</li>
        <li>监控页为 HTTPS 时 tunnel 必须 HTTPS/WSS，否则 iframe 会被混合内容拦截；</li>
        <li>构建期 CSP 需用 <span class="mono">frame-src</span> 放行 tunnel 源（<span class="mono">VITE_MONITOR_ARTHAS_ORIGIN</span>）；</li>
        <li>agent 启动加 <span class="mono">--tunnel-server ws://{tunnel-host}:7777/ws --agent-id {全局唯一}</span>，否则列表为空。</li>
      </ul>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { fetchArthasStatus, type ArthasStatus } from '@/api/monitor/arthas';

/** enabled 状态上报父级:enabled=false 时不渲染本 tab(§9.4)。 */
const emit = defineEmits<{ enabled: [boolean] }>();

const status = ref<ArthasStatus | null>(null);
const loading = ref(false);
const activeAgentId = ref('');
const iframeUrl = ref('');
/** CSP/混合内容拦截兜底:8s 未 load 视为被拦(§9.4 提示文案)。 */
const frameLoaded = ref(false);
const frameBlocked = ref(false);
let blockTimer: number | undefined;

const statusChipClass = computed(() => {
  if (!status.value) return 'chip-muted';
  if (!status.value.enabled) return 'chip-muted';
  return status.value.reachable ? 'chip-success' : 'chip-danger';
});

const statusChipText = computed(() => {
  if (!status.value) return '加载中';
  if (!status.value.enabled) return '未启用';
  return status.value.reachable ? '隧道可达' : '隧道不可达';
});

const statusText = computed(() => {
  if (!status.value) return '加载中…';
  if (!status.value.enabled) return 'Arthas 未开启';
  return status.value.reachable ? 'Arthas 已开启 · 可在页面内查看' : 'Arthas 已开启 · tunnel 当前不可达';
});

const statusMeta = computed(() => {
  const s = status.value;
  if (!s || !s.enabled) return '';
  const parts: string[] = [];
  if (s.tunnelVersion) parts.push(`tunnel ${s.tunnelVersion}`);
  if (s.consoleBaseUrl) parts.push(s.consoleBaseUrl);
  if (s.wsPort) parts.push(`WS :${s.wsPort}`);
  if (s.latencyMs !== null) parts.push(`探测 ${s.latencyMs}ms`);
  if (s.lastError) parts.push(s.lastError);
  return parts.join(' · ');
});

async function loadStatus(): Promise<void> {
  loading.value = true;
  try {
    status.value = await fetchArthasStatus();
    emit('enabled', Boolean(status.value?.enabled));
  } finally {
    loading.value = false;
  }
}

/** consoleBaseUrl 来自后端探测(/actuator/arthas),前端不自行拼接未知域;CSP frame-src 白名单构建期注入。 */
function openConsole(agentId: string): void {
  const base = status.value?.consoleBaseUrl;
  if (!base) return;
  activeAgentId.value = agentId;
  iframeUrl.value = `${base.replace(/\/$/, '')}?agentId=${encodeURIComponent(agentId)}&iframe=true`;
  frameLoaded.value = false;
  frameBlocked.value = false;
  window.clearTimeout(blockTimer);
  blockTimer = window.setTimeout(() => {
    if (!frameLoaded.value) frameBlocked.value = true;
  }, 8000);
}

function closeConsole(): void {
  iframeUrl.value = '';
  activeAgentId.value = '';
  window.clearTimeout(blockTimer);
}

onUnmounted(() => {
  window.clearTimeout(blockTimer);
});

onMounted(() => {
  void loadStatus();
});
</script>
