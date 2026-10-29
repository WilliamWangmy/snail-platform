<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';

type PanelName = 'layers' | 'models';

/** 图层面板条目（仅 UI 展示，功能暂未接入） */
const layerItems = [
  { key: 'no-fly', label: '禁飞区', accent: '#ef4444' },
  { key: 'fly-ok', label: '适飞区', accent: '#22c55e' },
  { key: 'admin', label: '行政区', accent: '#38bdf8' },
] as const;

/** 模型面板条目 */
const modelItems = [{ key: 'model-3d', label: '3D 模型', accent: '#a78bfa' }] as const;

const activePanel = ref<PanelName | null>(null);
const switches = ref<Record<string, boolean>>({
  'no-fly': false,
  'fly-ok': false,
  admin: false,
  'model-3d': false,
});

const rootRef = ref<HTMLDivElement>();

function togglePanel(name: PanelName) {
  activePanel.value = activePanel.value === name ? null : name;
}

function toggleSwitch(key: string) {
  switches.value[key] = !switches.value[key];
}

function onDocumentMousedown(e: MouseEvent) {
  if (rootRef.value && !rootRef.value.contains(e.target as Node)) {
    activePanel.value = null;
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') activePanel.value = null;
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentMousedown);
  document.addEventListener('keydown', onKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentMousedown);
  document.removeEventListener('keydown', onKeydown);
});
</script>

<template>
  <div
    ref="rootRef"
    class="map-toolbar"
  >
    <Transition name="panel">
      <!-- 图层面板 -->
      <div
        v-if="activePanel === 'layers'"
        class="toolbar-panel"
        role="group"
        aria-label="图层面板"
      >
        <div class="panel-title">
          图层
        </div>
        <button
          v-for="item in layerItems"
          :key="item.key"
          type="button"
          class="panel-row"
          role="switch"
          :aria-checked="switches[item.key]"
          @click="toggleSwitch(item.key)"
        >
          <svg
            class="row-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <template v-if="item.key === 'no-fly'">
              <circle
                cx="12"
                cy="12"
                r="9"
              />
              <line
                x1="5.8"
                y1="5.8"
                x2="18.2"
                y2="18.2"
              />
            </template>
            <template v-else-if="item.key === 'fly-ok'">
              <circle
                cx="12"
                cy="12"
                r="9"
              />
              <polyline points="8 12.5 11 15.5 16 9.5" />
            </template>
            <template v-else>
              <polygon points="3 6 9 3 15 6 21 3 21 18 15 21 9 18 3 21" />
              <line
                x1="9"
                y1="3"
                x2="9"
                y2="18"
              />
              <line
                x1="15"
                y1="6"
                x2="15"
                y2="21"
              />
            </template>
          </svg>
          <span class="row-label">{{ item.label }}</span>
          <span
            class="switch"
            :class="{ on: switches[item.key] }"
            :style="{ '--accent': item.accent }"
          >
            <span class="knob"></span>
          </span>
        </button>
      </div>

      <!-- 模型面板 -->
      <div
        v-else-if="activePanel === 'models'"
        class="toolbar-panel"
        role="group"
        aria-label="模型面板"
      >
        <div class="panel-title">
          模型
        </div>
        <button
          v-for="item in modelItems"
          :key="item.key"
          type="button"
          class="panel-row"
          role="switch"
          :aria-checked="switches[item.key]"
          @click="toggleSwitch(item.key)"
        >
          <svg
            class="row-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path
              d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"
            />
            <polyline points="3.3 7 12 12 20.7 7" />
            <line
              x1="12"
              y1="22"
              x2="12"
              y2="12"
            />
          </svg>
          <span class="row-label">{{ item.label }}</span>
          <span
            class="switch"
            :class="{ on: switches[item.key] }"
            :style="{ '--accent': item.accent }"
          >
            <span class="knob"></span>
          </span>
        </button>
      </div>
    </Transition>

    <div class="toolbar-actions">
      <button
        type="button"
        class="tool-btn"
        :class="{ active: activePanel === 'layers' }"
        :aria-expanded="activePanel === 'layers'"
        aria-label="图层"
        title="图层"
        @click="togglePanel('layers')"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <polygon points="12 2 2 7 12 12 22 7 12 2" />
          <polyline points="2 17 12 22 22 17" />
          <polyline points="2 12 12 17 22 12" />
        </svg>
      </button>
      <button
        type="button"
        class="tool-btn"
        :class="{ active: activePanel === 'models' }"
        :aria-expanded="activePanel === 'models'"
        aria-label="模型"
        title="模型"
        @click="togglePanel('models')"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path
            d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"
          />
          <polyline points="3.3 7 12 12 20.7 7" />
          <line
            x1="12"
            y1="22"
            x2="12"
            y2="12"
          />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.map-toolbar {
  position: absolute;
  right: 16px;
  bottom: 16px;
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

/* 主操作按钮 */
.toolbar-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.78);
  backdrop-filter: blur(10px);
  color: #cbd5e1;
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease;
}

.tool-btn svg {
  width: 22px;
  height: 22px;
}

.tool-btn:hover {
  border-color: rgba(56, 189, 248, 0.55);
  background: rgba(30, 41, 59, 0.9);
  color: #e2e8f0;
}

.tool-btn.active {
  border-color: #38bdf8;
  background: rgba(14, 116, 144, 0.35);
  color: #7dd3fc;
}

.tool-btn:focus-visible {
  outline: 2px solid #38bdf8;
  outline-offset: 2px;
}

/* 弹出面板：深色玻璃拟态卡片 */
.toolbar-panel {
  min-width: 188px;
  padding: 6px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.82);
  backdrop-filter: blur(12px);
  box-shadow: 0 8px 28px rgba(2, 6, 23, 0.55);
}

.panel-title {
  padding: 6px 10px 4px;
  color: #94a3b8;
  font-size: 12px;
  letter-spacing: 0.08em;
}

.panel-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 0 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #e2e8f0;
  font-size: 13px;
  line-height: 36px;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.panel-row:hover {
  background: rgba(255, 255, 255, 0.06);
}

.panel-row:focus-visible {
  outline: 2px solid #38bdf8;
  outline-offset: -2px;
}

.row-icon {
  flex: none;
  width: 16px;
  height: 16px;
  color: #94a3b8;
}

.row-label {
  flex: 1;
}

/* 开关 */
.switch {
  position: relative;
  flex: none;
  width: 34px;
  height: 20px;
  border-radius: 10px;
  background: #334155;
  transition: background-color 0.18s ease;
}

.switch .knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #f1f5f9;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.4);
  transition: transform 0.18s ease;
}

.switch.on {
  background: var(--accent, #22c55e);
}

.switch.on .knob {
  transform: translateX(14px);
}

/* 面板进出场动画 */
.panel-enter-active,
.panel-leave-active {
  transition:
    opacity 0.18s ease,
    transform 0.18s ease;
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (prefers-reduced-motion: reduce) {
  .tool-btn,
  .panel-row,
  .switch,
  .switch .knob,
  .panel-enter-active,
  .panel-leave-active {
    transition: none;
  }
}
</style>
