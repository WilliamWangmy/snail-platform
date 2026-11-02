<script setup lang="ts">
/**
 * bpmn-js 画布封装：编辑（Modeler）/ 只读（NavigatedViewer）双模式。
 * 监听 stage 尺寸变化（全屏切换/窗口缩放）自动 canvas.resized()；选中元素经 selection-changed 抛出。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import Modeler from 'bpmn-js/lib/Modeler';
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer';

import { flowCustomModules } from './customModules';
import { FLOWABLE_MODDLE_DESCRIPTOR } from './flowableExtension';

const props = defineProps<{
  /** BPMN XML（首帧或外部重载时导入） */
  xml: string;
  /** true 时为只读查看器（pan/zoom 可用，编辑关闭） */
  readonly?: boolean;
}>();

const emit = defineEmits<{
  /** 首次导入成功（用于回显完成信号） */
  imported: [];
  /** 导入失败（message） */
  importError: [message: string];
  /** 选中元素变化（businessObject.$type + id），null 表示清空 */
  selectionChanged: [element: { id: string; $type: string } | null];
  /** 本地编辑发生（脏标记来源） */
  changed: [];
}>();

const stageRef = ref<HTMLDivElement>();

let diagram: Modeler | NavigatedViewer | null = null;
let resizeObserver: ResizeObserver | null = null;
let imported = false;

async function render(): Promise<void> {
  if (!stageRef.value || !props.xml) return;

  diagram?.destroy();
  diagram = props.readonly
    ? new NavigatedViewer({ container: stageRef.value, moddleExtensions: { flowable: FLOWABLE_MODDLE_DESCRIPTOR } })
    : new Modeler({
        container: stageRef.value,
        moddleExtensions: { flowable: FLOWABLE_MODDLE_DESCRIPTOR },
        additionalModules: [flowCustomModules],
      });

  diagram.on('selection.changed', (event: { newSelection?: Array<{ id: string; businessObject: { $type: string } }> }) => {
    const first = event.newSelection?.[0];
    emit('selectionChanged', first ? { id: first.id, $type: first.businessObject.$type } : null);
  });

  if (!props.readonly) {
    diagram.on('commandStack.changed', () => {
      if (imported) {
        emit('changed');
      }
    });
  }

  try {
    await diagram.importXML(props.xml);
    imported = true;
    currentXml = props.xml;
    (diagram.get('canvas') as { zoom: (viewbox: string) => void }).zoom('fit-viewport');
    emit('imported');
  } catch (error) {
    emit('importError', error instanceof Error ? error.message : '流程图导入失败');
  }
}

let currentXml = '';

onMounted(() => {
  void render();

  // 尺寸变化（全屏切换 / ScaleContainer 缩放 / 窗口变化）→ 画布自适应
  resizeObserver = new ResizeObserver(() => {
    if (diagram && stageRef.value && stageRef.value.clientWidth > 0) {
      (diagram.get('canvas') as { resized: () => void }).resized();
    }
  });
  if (stageRef.value) {
    resizeObserver.observe(stageRef.value);
  }
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  diagram?.destroy();
  diagram = null;
});

watch(() => props.xml, (next) => {
  if (next && next !== currentXml) {
    void render();
  }
});

watch(() => props.readonly, () => {
  void render();
});

/** 导出 XML（保存/发布前调用） */
async function exportXml(): Promise<string> {
  if (!diagram || props.readonly) {
    throw new Error('只读模式下不可导出');
  }
  const result = await (diagram as Modeler).saveXML({ format: true });
  if (!result.xml) {
    throw new Error('流程图导出失败');
  }
  return result.xml;
}

defineExpose({ exportXml, getModeler: () => diagram });
</script>

<template>
  <div
    ref="stageRef"
    class="bpmn-canvas"
  ></div>
</template>

<style scoped lang="scss">
.bpmn-canvas {
  width: 100%;
  height: 100%;
  overflow: hidden;

  // 大屏深色主题下的画布底色（bpmn-js 默认白底，网格由内部样式控制）
  background: var(--el-bg-color);
}
</style>
