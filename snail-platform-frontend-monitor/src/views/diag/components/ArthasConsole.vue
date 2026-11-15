<template>
  <div class="arthas">
    <!-- 状态行:可达性 / 版本 / agent 数(§5.8) -->
    <div class="toolbar">
      <el-tag :type="statusType" size="small">{{ statusText }}</el-tag>
      <span v-if="status?.reachable" class="hint">
        tunnel {{ status.tunnelVersion || '未知版本' }} · {{ status.agentCount }} 个 agent · {{ status.clientConnectionCount }} 个连接
        <template v-if="status.latencyMs !== null"> · {{ status.latencyMs }}ms</template>
      </span>
      <span v-else-if="status?.lastError" class="hint error">{{ status.lastError }}</span>
      <el-button size="small" :loading="loading" @click="loadStatus">刷新</el-button>
    </div>

    <!-- Agent 列表:选中后在 iframe 打开对应控制台 -->
    <el-table v-if="status?.reachable && status.agents.length" :data="status.agents" size="small" border class="mb">
      <el-table-column prop="agentId" label="AgentId" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.agentId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="来源" min-width="180">
        <template #default="{ row }">{{ row.host || '未知来源' }}<template v-if="row.port">:{{ row.port }}</template></template>
      </el-table-column>
      <el-table-column prop="arthasVersion" label="Arthas 版本" width="120">
        <template #default="{ row }">{{ row.arthasVersion || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openConsole(row.agentId)">打开控制台</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else-if="status?.reachable" description="tunnel 已可达,当前无注册 agent" :image-size="72" />
    <el-empty v-else description="Arthas tunnel 未启用或不可达" :image-size="72" />

    <!-- 控制台 iframe:按 agentId 拼接 tunnel 控制台地址;CSP frame-src 限本域 + tunnel 域(DD-13) -->
    <template v-if="iframeUrl">
      <div class="iframe-bar">
        <span class="hint">agentId:{{ activeAgentId }} · 控制台直接操作目标 JVM,请谨慎执行命令</span>
        <el-button size="small" @click="closeConsole">关闭</el-button>
      </div>
      <iframe :src="iframeUrl" class="console-frame" sandbox="allow-scripts allow-same-origin allow-forms allow-popups" />
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue';
import { fetchArthasStatus, type ArthasStatus } from '@/api/monitor/arthas';

const status = ref<ArthasStatus | null>(null);
const loading = ref(false);
const activeAgentId = ref('');
const iframeUrl = ref('');

const statusType = computed(() => (status.value?.reachable ? 'success' : status.value?.enabled ? 'danger' : 'info'));
const statusText = computed(() => {
  if (!status.value) return '加载中';
  if (!status.value.enabled) return '未启用';
  return status.value.reachable ? 'tunnel 可达' : 'tunnel 不可达';
});

async function loadStatus(): Promise<void> {
  loading.value = true;
  try {
    status.value = await fetchArthasStatus();
  } finally {
    loading.value = false;
  }
}

/** consoleBaseUrl 来自后端探测(/actuator/arthas),前端不自行拼接未知域。 */
function openConsole(agentId: string): void {
  const base = status.value?.consoleBaseUrl;
  if (!base) {
    return;
  }
  activeAgentId.value = agentId;
  iframeUrl.value = `${base.replace(/\/$/, '')}?agentId=${encodeURIComponent(agentId)}`;
}

function closeConsole(): void {
  iframeUrl.value = '';
  activeAgentId.value = '';
}

onMounted(() => {
  void loadStatus();
});
</script>

<style scoped>
.arthas {
  min-height: 320px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: var(--text-secondary);
}

.hint.error {
  color: var(--danger-color, #f56c6c);
}

.mb {
  margin-bottom: 12px;
}

.mono {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}

.iframe-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.console-frame {
  width: 100%;
  height: 560px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--panel-bg);
}
</style>
