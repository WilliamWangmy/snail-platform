<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { signOutEverywhere } from '@/stores/auth';

const router = useRouter();

/** ssotoken 仅为站间一次性交接凭证,不是大屏登录态;登录门槛由 router 守卫 + JWT 把关。 */
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

/** 退出登录:先吊销服务端令牌再清本地,凭 redirect 记住来路,登录后回到当前视图。 */
async function onLogout(): Promise<void> {
  await signOutEverywhere();
  const target = window.location.pathname + window.location.search;
  await router.push({ name: 'login', query: { redirect: target } });
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
    <button
      type="button"
      class="switcher-btn logout"
      title="退出登录"
      @click="onLogout"
    >
      退出
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

.switcher-btn.logout {
  margin-left: 4px;
  color: #f87171;
}

.switcher-btn.logout:hover {
  border-color: rgba(248, 113, 113, 0.55);
  background: rgba(127, 29, 29, 0.45);
  color: #fecaca;
}
</style>
