<script setup lang="ts">
/**
 * 大屏等比缩放容器：设计基准 1920×1080，transform scale 等比缩放并居中。
 * 取 min(scaleX, scaleY)，比例不一致时留白；顶栏在 Layout 中，不参与缩放。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue';

const BASE_WIDTH = 1920;
const BASE_HEIGHT = 1080;

const scale = ref(1);
let resizeObserver: ResizeObserver | null = null;
const containerRef = ref<HTMLDivElement>();

function updateScale(): void {
  const el = containerRef.value;
  if (!el) return;
  const { clientWidth, clientHeight } = el;
  scale.value = Math.min(clientWidth / BASE_WIDTH, clientHeight / BASE_HEIGHT);
}

onMounted(() => {
  updateScale();
  resizeObserver = new ResizeObserver(updateScale);
  if (containerRef.value) {
    resizeObserver.observe(containerRef.value);
  }
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
});
</script>

<template>
  <div
    ref="containerRef"
    class="scale-viewport"
  >
    <div
      class="scale-stage"
      :style="{
        width: `${BASE_WIDTH}px`,
        height: `${BASE_HEIGHT}px`,
        transform: `scale(${scale})`,
      }"
    >
      <slot></slot>
    </div>
  </div>
</template>

<style scoped lang="scss">
.scale-viewport {
  overflow: hidden;
  width: 100%;
  height: 100%;
}

.scale-stage {
  transform-origin: center center;
}
</style>
