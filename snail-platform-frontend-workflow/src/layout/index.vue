<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

import TopNav from '@/layout/components/TopNav.vue';

const route = useRoute();
const fullBleed = computed(() => route.meta.fullBleed === true);
</script>

<template>
  <div class="shell">
    <TopNav />
    <div class="main">
      <main
        class="content"
        :class="{ 'content--full': fullBleed }"
      >
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.content--full {
  height: calc(100vh - var(--header-h));
  min-height: 0;
  flex: none;
  padding: 0;
  overflow: hidden;
}

@media (max-width: 860px) {
  .content--full {
    height: auto;
    overflow: visible;
  }
}
</style>
