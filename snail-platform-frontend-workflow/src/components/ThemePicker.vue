<script setup lang="ts">
import { ref } from 'vue';

import AppIcon from '@/components/AppIcon.vue';
import { FLOW_THEMES, currentTheme, setTheme, type FlowTheme } from '@/utils/theme';

const currentId = ref<FlowTheme>(currentTheme());

function onSelect(id: FlowTheme): void {
  currentId.value = setTheme(id);
}
</script>

<template>
  <ElPopover
    trigger="click"
    placement="bottom-end"
    :width="392"
    popper-class="theme-popper"
  >
    <template #reference>
      <button
        class="icon-btn"
        type="button"
        title="外观主题"
        aria-label="外观主题"
      >
        <AppIcon name="palette" />
      </button>
    </template>

    <div class="theme-panel-head">
      <div>
        <b>外观主题</b>
        <p>浅色、深色与色盲友好配色</p>
      </div>
      <AppIcon
        name="palette"
        :size="17"
      />
    </div>

    <div class="theme-grid">
      <button
        v-for="item in FLOW_THEMES"
        :key="item.id"
        type="button"
        class="theme-option"
        :class="{ active: item.id === currentId }"
        @click="onSelect(item.id)"
      >
        <span
          class="theme-preview"
          :style="{ background: item.preview.bg, borderColor: item.preview.line }"
        >
          <i :style="{ background: item.preview.sidebar }"></i>
          <b :style="{ background: item.preview.primary }"></b>
          <em :style="{ background: item.preview.card }"></em>
        </span>
        <span class="theme-meta">
          <b>{{ item.name }}</b>
          <span>{{ item.desc }}</span>
          <small v-if="item.cb">色盲友好</small>
        </span>
      </button>
    </div>

    <div class="theme-note">
      <AppIcon
        name="eye"
        :size="14"
      />
      <span>色盲友好主题以蓝 / 橙、紫 / 青替代红 / 绿表达状态，并保留文字与图形标记。</span>
    </div>
  </ElPopover>
</template>

<style scoped>
.theme-panel-head {
  display: flex;
  justify-content: space-between;
  padding: 1px 2px 12px;
}

.theme-panel-head b {
  display: block;
  color: var(--text-1);
  font-size: 12px;
}

.theme-panel-head p {
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: 9px;
}

.theme-panel-head > .ic {
  color: var(--teal);
}

.theme-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.theme-option {
  position: relative;
  padding: 7px;
  border: 1px solid var(--line-strong);
  border-radius: 10px;
  background: var(--bg-2);
  color: var(--text-1);
  text-align: left;
  cursor: pointer;
}

.theme-option:hover {
  border-color: var(--teal);
}

.theme-option.active {
  border-color: var(--teal);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--teal) 13%, transparent);
}

.theme-option.active::after {
  content: '✓';
  position: absolute;
  right: 6px;
  top: 6px;
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--teal);
  color: var(--ink);
  font-size: 9px;
  font-weight: 800;
}

.theme-preview {
  position: relative;
  display: block;
  height: 52px;
  border: 1px solid;
  border-radius: 6px;
  overflow: hidden;
}

.theme-preview i {
  position: absolute;
  inset: 0 auto 0 0;
  width: 25%;
}

.theme-preview b {
  position: absolute;
  left: 34%;
  top: 11px;
  width: 42%;
  height: 5px;
  border-radius: 3px;
}

.theme-preview em {
  position: absolute;
  left: 34%;
  top: 25px;
  width: 57%;
  height: 18px;
  border-radius: 4px;
}

.theme-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px 6px;
  margin-top: 7px;
}

.theme-meta b {
  font-size: 10.5px;
}

.theme-meta span {
  color: var(--text-3);
  font-size: 8.5px;
}

.theme-meta small {
  margin-left: auto;
  padding: 2px 4px;
  border-radius: 3px;
  background: var(--teal-soft);
  color: var(--teal);
  font-size: 7.5px;
}

.theme-note {
  display: flex;
  gap: 7px;
  margin-top: 11px;
  padding: 9px;
  border-radius: 8px;
  background: var(--teal-soft);
  color: var(--text-3);
  font-size: 8.5px;
  line-height: 1.55;
}

.theme-note > .ic {
  margin-top: 1px;
  color: var(--teal);
}
</style>
