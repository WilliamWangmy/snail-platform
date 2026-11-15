<template>
  <svg
    :width="'100%'"
    :height="height"
    :viewBox="`0 0 ${width} ${height}`"
    preserveAspectRatio="none"
    class="svg-line-chart"
  >
    <!-- 横向网格 + y 轴刻度 -->
    <template v-for="i in 5" :key="'g' + i">
      <line
        :x1="padL"
        :y1="gridY(i - 1)"
        :x2="width - padR"
        :y2="gridY(i - 1)"
        class="lc-grid"
      />
      <text
        :x="padL - 6"
        :y="gridY(i - 1) + 3.5"
        text-anchor="end"
        font-size="9.5"
        class="lc-tick"
      >{{ tickText(i - 1) }}</text>
    </template>
    <!-- x 轴标签 -->
    <text
      v-for="(lb, i) in labels"
      :key="'l' + i"
      :x="labelX(i)"
      :y="height - 7"
      text-anchor="middle"
      font-size="9.5"
      class="lc-tick"
    >{{ lb }}</text>
    <!-- 序列:面积 + 折线 + 隐形 title 圆点 -->
    <template v-for="s in seriesWithPts" :key="s.name">
      <path
        v-if="s.area"
        :d="s.areaPath"
        :fill="s.color"
        opacity="0.10"
      />
      <path
        :d="s.path"
        fill="none"
        :stroke="s.color"
        stroke-width="1.8"
      />
      <circle
        v-for="(p, i) in s.pts"
        :key="s.name + i"
        :cx="p[0]"
        :cy="p[1]"
        r="6"
        fill="transparent"
      >
        <title>{{ s.name }} {{ labels[i] ?? i }}: {{ s.values[i] }}{{ s.unit || '' }}</title>
      </circle>
    </template>
  </svg>
</template>

<script lang="ts" setup>
/** 自绘折线图:照原型 lineChart() 算法移植(网格 5 分、面积 10% 透明度、title 圆点提示)。 */
import { computed } from 'vue';

export interface LineSeries {
  name: string;
  values: number[];
  color: string;
  area?: boolean;
  unit?: string;
}

const props = withDefaults(
  defineProps<{
    series: LineSeries[];
    labels?: string[];
    height?: number;
    width?: number;
    min?: number;
    max?: number;
    zero?: boolean;
    fmt?: (v: number) => string;
  }>(),
  { labels: () => [], height: 210, width: 520, fmt: undefined },
);

const padL = 40;
const padR = 12;
const padT = 12;
const padB = 24;

const bounds = computed(() => {
  const all = props.series.flatMap((s) => s.values);
  let lo = props.min ?? Math.min(...all, 0);
  let hi = props.max ?? Math.max(...all, 0);
  if (props.zero) lo = 0;
  const span = hi - lo || 1;
  return { lo: lo - span * 0.08, hi: hi + span * 0.08 };
});

const n = computed(() => props.series[0]?.values.length ?? 0);

function X(i: number): number {
  return padL + (i / Math.max(1, n.value - 1)) * (props.width - padL - padR);
}

function Y(v: number): number {
  const { lo, hi } = bounds.value;
  return padT + (1 - (v - lo) / (hi - lo)) * (props.height - padT - padB);
}

function gridY(i: number): number {
  return padT + i * (props.height - padT - padB) / 4;
}

function tickText(i: number): string {
  const { lo, hi } = bounds.value;
  const v = hi - i * (hi - lo) / 4;
  return props.fmt ? props.fmt(v) : v.toFixed(0);
}

function labelX(i: number): number {
  return padL + (i / Math.max(1, props.labels.length - 1)) * (props.width - padL - padR);
}

const seriesWithPts = computed(() =>
  props.series.map((s) => {
    const pts = s.values.map((v, i) => [X(i), Y(v)] as [number, number]);
    const path = pts.map((p, i) => (i ? 'L' : 'M') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
    const areaPath =
      s.area && pts.length
        ? `${path} L ${X(s.values.length - 1).toFixed(1)} ${props.height - padB} L ${X(0).toFixed(1)} ${props.height - padB} Z`
        : '';
    return { ...s, pts, path, areaPath };
  }),
);
</script>

<style>
.svg-line-chart .lc-grid {
  stroke: var(--app-line);
  stroke-width: 1;
}

.svg-line-chart .lc-tick {
  fill: var(--app-muted);
}
</style>
