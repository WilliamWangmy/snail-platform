<script setup lang="ts">
import { onMounted, ref } from 'vue';

/** 大屏无登录态:收下跳转带来的一次性 ssotoken 仅用于再交接给管理端/流程平台。 */
const STORAGE_KEY = 'snail-sso-token';

const ADMIN_URL = import.meta.env.VITE_ADMIN_URL ?? 'http://localhost:8000/';
const FLOW_URL = import.meta.env.VITE_FLOW_URL ?? 'http://localhost:8002/';

const targets = [
  { key: 'admin', name: '管理端', desc: '多租户运营控制台', url: ADMIN_URL },
  { key: 'flow', name: '流程平台', desc: '流程控制台', url: FLOW_URL },
] as const;

const token = ref('');

onMounted(() => {
  const fromUrl = new URLSearchParams(location.search).get('ssotoken');
  if (fromUrl) {
    token.value = fromUrl;
    localStorage.setItem(STORAGE_KEY, fromUrl);
    // 落地即剥离参数,避免 token 留在地址栏与历史记录
    const url = new URL(location.href);
    url.searchParams.delete('ssotoken');
    window.history.replaceState(null, '', url);
  } else {
    token.value = localStorage.getItem(STORAGE_KEY) ?? '';
  }
});

function go(url: string): void {
  const joiner = url.includes('?') ? '&' : '?';
  const suffix = token.value ? `ssotoken=${encodeURIComponent(token.value)}` : '';
  window.open(`${url}${joiner}${suffix}`, '_blank', 'noopener');
}
</script>

<template>
  <div
    class="system-switcher"
    role="group"
    aria-label="切换系统"
  >
    <span class="switcher-label">SNAIL</span>
    <button
      v-for="item in targets"
      :key="item.key"
      type="button"
      class="switcher-btn"
      :title="item.desc"
      @click="go(item.url)"
    >
      {{ item.name }}
    </button>
  </div>
</template>

<style scoped>
.system-switcher {
  position: absolute;
  top: 16px;
  right: 16px;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.78);
  backdrop-filter: blur(10px);
}

.switcher-label {
  padding: 0 6px;
  color: rgba(125, 211, 252, 0.6);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.switcher-btn {
  padding: 5px 12px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  color: #cbd5e1;
  font-size: 11.5px;
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease;
}

.switcher-btn:hover {
  border-color: rgba(56, 189, 248, 0.55);
  background: rgba(30, 41, 59, 0.9);
  color: #e2e8f0;
}

.switcher-btn:focus-visible {
  outline: 2px solid #38bdf8;
  outline-offset: 1px;
}
</style>
