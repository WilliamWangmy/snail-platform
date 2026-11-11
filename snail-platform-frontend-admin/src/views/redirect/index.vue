<script setup lang="ts">
import { nextTick, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useTabsStore } from '@/stores/tabs';

const route = useRoute();
const router = useRouter();
const tabs = useTabsStore();

/** 刷新中转:挂载即恢复 KeepAlive include(旧实例已在跳入时被裁剪),再跳回目标页全新挂载。 */
onMounted(async () => {
  const target = `/${(route.params.path as string | undefined) ?? ''}`;
  tabs.restoreCache();
  await nextTick();
  router.replace({ path: target, query: route.query });
});
</script>

<template>
  <div class="redirect-loading">
    <span class="redirect-spinner"></span>
    <span>页面刷新中…</span>
  </div>
</template>

<style scoped>
.redirect-loading {
  display: flex;
  height: 240px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--app-muted);
  font-size: 13px;
}

.redirect-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--app-line);
  border-top-color: var(--app-primary);
  border-radius: 50%;
  animation: redirect-spin 0.7s linear infinite;
}

@keyframes redirect-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
