<script setup lang="ts">
/**
 * 实例流程图：只读 bpmn-js 画布 + 节点/连线状态着色。
 * 用例：实例抽屉与任务抽屉的「流程图」页签；节点状态来自轨迹接口的 diagram，连线状态按两端节点推导。
 */
import { computed, ref } from 'vue';

import type { WorkflowDiagramNodeState, WorkflowTraceVO } from '@/api/workflow/instance';
import AppIcon from '@/components/AppIcon.vue';
import BpmnCanvas from '@/views/modeler/designer/BpmnCanvas.vue';

const props = withDefaults(
  defineProps<{
    trace: WorkflowTraceVO | null;
    loading?: boolean;
  }>(),
  { loading: false }
);

interface DiagramMarker {
  id: string;
  className: string;
}

const canvas = ref<InstanceType<typeof BpmnCanvas>>();

const xml = computed(() => props.trace?.diagram?.xml ?? '');
const nodes = computed(() => props.trace?.diagram?.nodes ?? []);
const nodeStates = computed<Record<string, WorkflowDiagramNodeState>>(() =>
  Object.fromEntries(nodes.value.map((node) => [node.activityId, node.state] as const))
);

const STATE_CLASS: Record<WorkflowDiagramNodeState, string> = {
  DONE: 'done',
  ACTIVE: 'active',
  REJECTED: 'rejected',
  WITHDRAWN: 'withdrawn',
};

const markers = computed<DiagramMarker[]>(() => {
  const result: DiagramMarker[] = nodes.value.map((node) => ({
    id: node.activityId,
    className: `trace-node-${STATE_CLASS[node.state]}`,
  }));
  if (!xml.value) return result;

  const doc = new DOMParser().parseFromString(xml.value, 'text/xml');
  const flows = doc.getElementsByTagNameNS('*', 'sequenceFlow');
  for (let index = 0; index < flows.length; index += 1) {
    const flow = flows.item(index);
    const id = flow?.getAttribute('id');
    const source = flow?.getAttribute('sourceRef') ?? '';
    const target = flow?.getAttribute('targetRef') ?? '';
    if (!id) continue;
    const className = flowStateClass(nodeStates.value[source], nodeStates.value[target]);
    if (className) result.push({ id, className });
  }
  return result;
});

function flowStateClass(
  source?: WorkflowDiagramNodeState,
  target?: WorkflowDiagramNodeState
): string {
  if (source === 'REJECTED') return 'trace-flow-rejected';
  if (target === 'ACTIVE') return 'trace-flow-active';
  if (source === 'WITHDRAWN' || target === 'WITHDRAWN') return 'trace-flow-withdrawn';
  const sourceVisited = source === 'DONE' || source === 'REJECTED';
  const targetVisited = target === 'DONE' || target === 'REJECTED';
  return sourceVisited && targetVisited ? 'trace-flow-done' : '';
}

const legend = computed(() => {
  const status = props.trace?.instance.status;
  const items: Array<{ key: string; label: string }> = [{ key: 'done', label: '已通过' }];
  if (status === 'RUNNING') items.push({ key: 'active', label: '当前审批' });
  if (status === 'REJECTED') items.push({ key: 'rejected', label: '驳回路径' });
  if (status === 'WITHDRAWN') items.push({ key: 'withdrawn', label: '撤销终止' });
  items.push({ key: 'pending', label: '未执行' });
  return items;
});

function zoomBy(step: number): void {
  canvas.value?.zoomBy(step);
}

function fitCanvas(): void {
  canvas.value?.fitCanvas();
}
</script>

<template>
  <div class="flow-diagram">
    <div class="diagram-toolbar">
      <div
        class="diagram-legend"
        aria-label="流程图图例"
      >
        <span
          v-for="item in legend"
          :key="item.key"
          class="legend-item"
        >
          <i
            class="legend-dot"
            :class="`legend-dot--${item.key}`"
          />{{ item.label }}
        </span>
      </div>
      <div class="diagram-tools">
        <button
          class="icon-btn"
          type="button"
          aria-label="缩小流程图"
          title="缩小"
          @click="zoomBy(-15)"
        >
          <AppIcon name="zoom-out" />
        </button>
        <button
          class="icon-btn"
          type="button"
          aria-label="适应画布"
          title="适应画布"
          @click="fitCanvas"
        >
          <AppIcon name="maximize" />
        </button>
        <button
          class="icon-btn"
          type="button"
          aria-label="放大流程图"
          title="放大"
          @click="zoomBy(15)"
        >
          <AppIcon name="zoom-in" />
        </button>
      </div>
    </div>

    <div
      v-loading="loading"
      class="diagram-stage"
    >
      <BpmnCanvas
        v-if="xml"
        ref="canvas"
        :xml="xml"
        :readonly="true"
        :markers="markers"
      />
      <div
        v-else-if="!loading"
        class="diagram-empty"
      >
        <el-empty
          class="flow-empty"
          description="流程图不可用"
        >
          <template #image>
            <span class="empty-icon"><AppIcon name="workflow" /></span>
          </template>
          <p class="empty-hint">
            流程定义原文缺失或读取失败，节点轨迹仍可查看。
          </p>
        </el-empty>
      </div>
    </div>

    <p class="diagram-foot">
      <AppIcon name="hand" />拖拽平移 · 滚轮缩放；配色随系统主题切换。
    </p>
  </div>
</template>

<style scoped lang="scss">
.flow-diagram {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.diagram-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}

.diagram-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px 12px;
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-3);
  font-size: 10px;
  white-space: nowrap;
}

.legend-dot {
  width: 8px;
  height: 8px;
  border: 1.5px solid var(--line-strong);
  border-radius: 50%;
  background: var(--panel-solid);
}

.legend-dot--done {
  border-color: var(--teal);
  background: var(--teal);
  box-shadow: 0 0 6px var(--teal-a35);
}

.legend-dot--active {
  border-color: var(--blue);
  background: var(--blue);
  box-shadow: 0 0 6px var(--blue-a6);
}

.legend-dot--rejected {
  border-color: var(--danger);
  background: var(--danger);
  box-shadow: 0 0 6px var(--danger-a4);
}

.legend-dot--withdrawn {
  border-color: var(--amber);
  background: var(--amber);
  box-shadow: 0 0 6px var(--amber-a35);
}

.diagram-tools {
  display: flex;
  align-items: center;
  gap: 4px;
}

.diagram-stage {
  position: relative;
  height: clamp(320px, 46vh, 460px);
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 12px;
  background-color: var(--canvas-surface-subtle);
  background-image: radial-gradient(var(--line) 1px, transparent 1px);
  background-size: 18px 18px;

  :deep(.bpmn-canvas) {
    min-width: 0;
    min-height: 0;
  }
}

.diagram-empty {
  display: flex;
  height: 100%;
  align-items: center;
  justify-content: center;
}

.diagram-foot {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: var(--text-3);
  font-size: 10px;
}

$trace-states: (
  done: var(--teal),
  active: var(--blue),
  rejected: var(--danger),
  withdrawn: var(--amber),
);

/* 渲染器把填充/描边写成内联样式，状态色必须 !important 才能覆盖 */
@each $name, $color in $trace-states {
  .diagram-stage :deep(.djs-element.trace-node-#{$name} .djs-visual > :first-child) {
    fill: color-mix(in srgb, #{$color} 16%, var(--canvas-surface)) !important;
    stroke: #{$color} !important;
    stroke-width: 2.5px !important;
  }

  .diagram-stage :deep(.djs-element.trace-flow-#{$name} .djs-visual > path) {
    stroke: #{$color} !important;
    stroke-width: 2px !important;
  }
}

.diagram-stage :deep(.djs-element.trace-node-active .djs-visual > :first-child) {
  filter: drop-shadow(0 0 6px color-mix(in srgb, var(--blue) 55%, transparent));
  animation: trace-node-pulse 2.4s ease-in-out infinite;
}

@keyframes trace-node-pulse {
  0%,
  100% {
    stroke-opacity: 1;
  }

  50% {
    stroke-opacity: 0.5;
  }
}

@media (prefers-reduced-motion: reduce) {
  .diagram-stage :deep(.djs-element.trace-node-active .djs-visual > :first-child) {
    animation: none;
  }
}

@media (max-width: 560px) {
  .diagram-stage {
    height: 300px;
  }

  .diagram-toolbar {
    align-items: flex-start;
  }
}
</style>
