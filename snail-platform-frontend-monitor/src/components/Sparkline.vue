<template>
  <svg
    :width="width"
    :height="height"
    :viewBox="`0 0 ${width} ${height}`"
  >
    <path
      :d="path"
      fill="none"
      :stroke="color"
      stroke-width="1.6"
    />
  </svg>
</template>

<script lang="ts" setup>
/** 迷你趋势线:照原型 sparkline() 移植(指标卡右下角)。 */
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    vals: number[];
    color: string;
    width?: number;
    height?: number;
  }>(),
  { width: 110, height: 30 },
);

const path = computed(() => {
  const vals = props.vals.length ? props.vals : [0, 0];
  const min = Math.min(...vals);
  const max = Math.max(...vals);
  const span = max - min || 1;
  const pts = vals.map((v, i) => [
    (i / (vals.length - 1)) * props.width,
    props.height - 3 - ((v - min) / span) * (props.height - 8),
  ]);
  return pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
});
</script>
