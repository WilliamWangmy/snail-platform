<template>
  <svg
    width="100%"
    :height="height"
    :viewBox="`0 0 ${width} ${height}`"
    preserveAspectRatio="none"
    class="svg-bar-chart"
  >
    <!-- 横向网格 + 刻度 -->
    <template v-for="i in 4" :key="'g' + i">
      <line
        :x1="padL"
        :y1="gridY(i - 1)"
        :x2="width - padR"
        :y2="gridY(i - 1)"
        class="bc-grid"
      />
      <text
        :x="padL - 5"
        :y="gridY(i - 1) + 3"
        text-anchor="end"
        font-size="9"
        class="bc-tick"
      >{{ Math.round(maxVal - (i - 1) * maxVal / 3) }}</text>
    </template>
    <!-- 分组柱 + 组标签 -->
    <template v-for="(gr, gi) in groups" :key="gr.label">
      <rect
        v-for="(v, vi) in gr.values"
        :key="gr.label + vi"
        :x="barX(gi, vi)"
        :y="height - padB - barH(v)"
        :width="barWidth - 2"
        :height="barH(v)"
        rx="2"
        :fill="gr.colors[vi]"
      >
        <title>{{ gr.label }} {{ gr.names?.[vi] ?? vi }}: {{ v }}</title>
      </rect>
      <text
        :x="groupCenter(gi)"
        :y="height - 6"
        text-anchor="middle"
        font-size="9"
        class="bc-tick"
      >{{ gr.label }}</text>
    </template>
  </svg>
</template>

<script lang="ts" setup>
/** 自绘分组柱状图:照原型 barChart() 算法移植(登录趋势等)。 */
import { computed } from 'vue';

export interface BarGroup {
  label: string;
  values: number[];
  colors: string[];
  names?: string[];
}

const props = withDefaults(
  defineProps<{
    groups: BarGroup[];
    height?: number;
    width?: number;
  }>(),
  { height: 170, width: 420 },
);

const padL = 34;
const padR = 8;
const padT = 10;
const padB = 22;

const maxVal = computed(() => Math.max(...props.groups.flatMap((g) => g.values)) * 1.15 || 1);
const groupWidth = computed(() => (props.width - padL - padR) / Math.max(1, props.groups.length));
const barWidth = computed(() => {
  const per = props.groups[0]?.values.length ?? 1;
  return Math.min(16, groupWidth.value / (per + 1));
});

function groupCenter(gi: number): number {
  return padL + gi * groupWidth.value + groupWidth.value / 2;
}

function barX(gi: number, vi: number): number {
  const per = props.groups[gi]?.values.length ?? 1;
  return groupCenter(gi) - (per * barWidth.value) / 2 + vi * barWidth.value;
}

function barH(v: number): number {
  return (v / maxVal.value) * (props.height - padT - padB);
}

function gridY(i: number): number {
  return padT + i * (props.height - padT - padB) / 3;
}
</script>

<style>
.svg-bar-chart .bc-grid {
  stroke: var(--app-line);
}

.svg-bar-chart .bc-tick {
  fill: var(--app-muted);
}
</style>
