import * as echarts from 'echarts/core';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useThemeStore } from '@/stores/theme';

echarts.use([LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);

/** ECharts 单点封装:主题令牌驱动配色,主题切换时重建实例(§4.8)。 */
export function useChart(render: (chart: echarts.ECharts, colors: string[]) => void) {
  const el = ref<HTMLElement | null>(null);
  let instance: echarts.ECharts | null = null;

  function mount() {
    if (!el.value) return;
    instance?.dispose();
    const theme = useThemeStore().current;
    instance = echarts.init(el.value, undefined, { renderer: 'canvas' });
    const colors = [
      theme.primary,
      theme.chart[1] ?? theme.info,
      theme.accent,
      theme.info,
    ];
    render(instance, colors);
  }

  function update(renderFn?: (chart: echarts.ECharts, colors: string[]) => void) {
    if (!instance || !el.value) {
      mount();
      return;
    }
    if (renderFn) renderFn(instance, chartColors());
  }

  function chartColors(): string[] {
    const theme = useThemeStore().current;
    return [
      theme.primary,
      theme.chart[1] ?? theme.info,
      theme.accent,
      theme.info,
    ];
  }

  function resize() {
    instance?.resize();
  }

  onMounted(() => {
    mount();
    window.addEventListener('resize', resize);
  });

  onBeforeUnmount(() => {
    window.removeEventListener('resize', resize);
    instance?.dispose();
    instance = null;
  });

  // 主题切换重建实例(配色来自 CSS 变量)
  watch(() => useThemeStore().currentId, () => mount());

  return { el, mount, update, resize };
}

export { echarts };
