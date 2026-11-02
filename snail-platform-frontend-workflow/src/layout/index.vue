<script setup lang="ts">
/**
 * 大屏框架：顶栏（固定像素，不缩放）+ 内容区（ScaleContainer 等比缩放）。
 */
import ScaleContainer from '@/layout/components/ScaleContainer.vue';
import TopNav from '@/layout/components/TopNav.vue';
</script>

<template>
  <div class="app-layout">
    <TopNav />
    <main class="app-content">
      <ScaleContainer>
        <RouterView />
      </ScaleContainer>
    </main>
  </div>
</template>

<style scoped lang="scss">
.app-layout {
  display: flex;
  flex-direction: column;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
}

.app-content {
  flex: 1;
  min-height: 0;
}
</style>
