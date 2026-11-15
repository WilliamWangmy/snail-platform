<template>
  <div class="app">
    <TopNav />
    <div class="content">
      <div class="page-head">
        <div>
          <div class="eyebrow">Snail Platform · Monitor</div>
          <h1 class="page-title">{{ pageTitle }}</h1>
        </div>
      </div>
      <RouterView />
    </div>
    <footer class="app-foot">
      <span>snail-platform · 系统监控</span>
      <span>前端 :8003 · 后端 snail-function-monitor · 一级导航为静态路由（meta.perm 控显隐）</span>
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import TopNav from './components/TopNav.vue';

const route = useRoute();
const pageTitle = computed(() => (route.meta.title as string) ?? '');
</script>
