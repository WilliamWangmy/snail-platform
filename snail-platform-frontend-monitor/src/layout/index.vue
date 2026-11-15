<template>
  <div class="shell">
    <TopNav />
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<script lang="ts" setup>
import TopNav from './components/TopNav.vue';
</script>

<style scoped>
.shell {
  min-height: 100vh;
  background: var(--app-bg);
}
.content {
  max-width: 1440px;
  margin: 0 auto;
}
</style>
