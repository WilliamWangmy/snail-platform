<script setup lang="ts">
import { computed } from 'vue';

import type { WeatherSnapshot } from '@/config/weather';

/** 纯展示组件:数据由父级注入,取数方式(静态/接口)不进本组件。 */
const props = defineProps<{ snapshot: WeatherSnapshot }>();

/** AQI 分档着色,色值与告警语义对齐:优=绿,良=琥珀,以上=红。 */
const aqiClass = computed(() => {
  if (props.snapshot.aqiLevel === '优') return 'good';
  if (props.snapshot.aqiLevel === '良') return 'warn';
  return 'bad';
});
</script>

<template>
  <div
    class="wx-card"
    :aria-label="`天气：${snapshot.city} ${snapshot.text} ${snapshot.tempC} 摄氏度`"
  >
    <svg
      class="wx-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.7"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <circle
        cx="8.5"
        cy="9"
        r="3.2"
      />
      <path d="M8.5 3.2v1.4M8.5 13.4v1.4M3.6 9h1.4M12 9h1.4M4.9 5.4l1 1M11.1 5.4l-1 1" />
      <path d="M10 17.5h7.6a3.2 3.2 0 0 0 .4-6.36A4.4 4.4 0 0 0 13.4 11" />
      <path d="M10 17.5a3 3 0 0 0 0 6h7.6" />
    </svg>

    <span class="wx-temp">
      {{ snapshot.tempC }}<sup>°C</sup>
    </span>

    <span class="wx-col">
      <span class="wx-city">{{ snapshot.city }} · {{ snapshot.text }}</span>
      <span class="wx-sub">更新 {{ snapshot.updatedAt }}<span v-if="!snapshot.live"> · 静态</span></span>
    </span>

    <span class="wx-metrics">
      <span class="wx-metric">
        <span class="k">风</span>
        <span class="v">{{ snapshot.wind }}</span>
      </span>
      <span class="wx-metric">
        <span class="k">湿度</span>
        <span class="v">{{ snapshot.humidity }}%</span>
      </span>
      <span class="wx-metric">
        <span class="k">AQI</span>
        <span
          class="v"
          :class="aqiClass"
        >{{ snapshot.aqi }} {{ snapshot.aqiLevel }}</span>
      </span>
    </span>
  </div>
</template>

<style scoped>
.wx-card {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  height: 40px;
  padding: 0 12px 0 10px;
  border: 1px solid rgba(34, 211, 238, 0.28);
  clip-path: polygon(0 0, calc(100% - 10px) 0, 100% 10px, 100% 100%, 10px 100%, 0 calc(100% - 10px));
  background: rgba(6, 24, 44, 0.7);
}

.wx-icon {
  flex: none;
  width: 22px;
  height: 22px;
  color: #fbbf24;
}

.wx-temp {
  color: #e0f2fe;
  font-size: 19px;
  font-weight: 600;
  line-height: 1;
}

.wx-temp sup {
  color: #7dd3fc;
  font-size: 10px;
}

.wx-col {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.wx-city {
  color: #cbd5e1;
  font-size: 11.5px;
  white-space: nowrap;
}

.wx-sub {
  color: #7d8ba1;
  font-family: 'Cascadia Mono', ui-monospace, Consolas, monospace;
  font-size: 9.5px;
  letter-spacing: 0.06em;
}

.wx-metrics {
  display: flex;
  gap: 9px;
  padding-left: 10px;
  border-left: 1px solid rgba(148, 163, 184, 0.2);
}

.wx-metric {
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.wx-metric .k {
  color: #64748b;
  font-size: 8.5px;
  letter-spacing: 0.12em;
}

.wx-metric .v {
  color: #a5f3fc;
  font-family: 'Cascadia Mono', ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: nowrap;
}

.wx-metric .v.good {
  color: #4ade80;
}

.wx-metric .v.warn {
  color: #fbbf24;
}

.wx-metric .v.bad {
  color: #f87171;
}

/* 与顶栏窄屏阶梯同步:1700 以下先让位给菜单与会话元信息,只留温度与现象 */
@media (max-width: 1700px) {
  .wx-metrics {
    display: none;
  }
}
</style>
