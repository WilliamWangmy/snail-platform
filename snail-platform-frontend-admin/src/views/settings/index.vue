<script setup lang="ts">
import { useRoute } from 'vue-router';
import { useLayoutStore } from '@/stores/layout';
import { useTabsStore } from '@/stores/tabs';
import { useThemeStore } from '@/stores/theme';

/** 组件名与路由 meta.viewName 对应,多页签 KeepAlive include 依此缓存。 */
defineOptions({ name: 'SettingsView' });

const route = useRoute();
const layout = useLayoutStore();
const tabs = useTabsStore();
const theme = useThemeStore();

/** 切到单页时收敛页签只留当前页,再开页签时不会冒出一串旧页签。 */
function handleTabModeChange(value: string | number | boolean): void {
  const next = value === true;
  layout.setTabMode(next);
  if (!next) tabs.closeOthers(route.fullPath);
}
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <div class="eyebrow">
          SETTINGS
        </div>
        <h1>系统设置</h1>
        <p>页签展示与主题外观,设置即时生效并自动保存。</p>
      </div>
    </div>

    <ElCard
      shadow="never"
      class="settings-card"
    >
      <template #header>
        <div class="table-card-head">
          <h3>
            页签展示
            <span>TAB DISPLAY</span>
          </h3>
        </div>
      </template>

      <div class="settings-row">
        <div class="settings-row-text">
          <b>多页签模式</b>
          <span>{{ layout.tabMode ? '已开启：打开的菜单以页签展示，可来回切换、关闭与刷新，页面状态保留' : '已关闭：点开菜单直接替换当前页，不保留页签' }}</span>
        </div>
        <ElSwitch
          :model-value="layout.tabMode"
          aria-label="多页签模式开关"
          @change="handleTabModeChange"
        />
      </div>

      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="关闭多页签时，除当前页外的页签会被清理；重新开启后从当前页开始重新积累。"
        class="settings-alert"
      />
    </ElCard>

    <ElCard
      shadow="never"
      class="settings-card"
    >
      <template #header>
        <div class="table-card-head">
          <h3>
            系统主题
            <span>APPEARANCE</span>
          </h3>
        </div>
      </template>

      <div class="theme-grid">
        <button
          v-for="item in theme.themes"
          :key="item.id"
          type="button"
          class="theme-option"
          :class="{ active: item.id === theme.currentId }"
          :aria-label="`切换主题：${item.name}`"
          @click="theme.select(item.id)"
        >
          <span
            class="theme-preview"
            :style="{ background: item.bg, borderColor: item.line }"
          >
            <i :style="{ background: item.sidebar }"></i>
            <b :style="{ background: item.primary }"></b>
            <em :style="{ background: item.card }"></em>
            <u :style="{ background: item.accent }"></u>
          </span>
          <span class="theme-meta">
            <b>{{ item.name }}</b>
            <span>{{ item.desc }}</span>
            <small
              v-if="item.cb"
              class="cb-badge"
            >色盲友好</small>
            <small
              v-else-if="item.id === theme.currentId"
              class="current-badge"
            >当前使用</small>
          </span>
        </button>
      </div>

      <ElAlert
        type="info"
        :closable="false"
        show-icon
        title="色盲友好主题以蓝 / 橙、紫 / 青替代红 / 绿表达状态，并保留文字与图形标记。"
        class="settings-alert"
      />
    </ElCard>
  </div>
</template>

<style scoped>
.settings-card + .settings-card {
  margin-top: 16px;
}

.settings-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 0 4px;
}

.settings-row-text b {
  display: block;
  color: var(--app-ink);
  font-size: 13px;
}

.settings-row-text span {
  display: block;
  margin-top: 4px;
  color: var(--app-muted);
  font-size: 11.5px;
}

.settings-alert {
  margin-top: 14px;
  --el-alert-bg-color: var(--app-soft);
}

/* 主题选择:预览构图沿用原顶栏主题面板,页面内放大一号 */
.theme-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.theme-option {
  position: relative;
  padding: 9px;
  border: 1px solid var(--app-line);
  border-radius: 12px;
  background: var(--app-soft);
  color: var(--app-ink);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.theme-option:hover {
  border-color: var(--app-primary);
}

.theme-option.active {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 2px rgba(var(--app-primary-rgb), 0.13);
}

.theme-option.active::after {
  content: '✓';
  position: absolute;
  right: 8px;
  top: 8px;
  display: grid;
  place-items: center;
  width: 17px;
  height: 17px;
  border-radius: 50%;
  background: var(--app-primary);
  color: var(--app-on-primary);
  font-size: 9px;
  font-weight: 800;
}

.theme-preview {
  position: relative;
  display: block;
  height: 64px;
  border: 1px solid;
  border-radius: 8px;
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
  top: 14px;
  width: 42%;
  height: 6px;
  border-radius: 3px;
}

.theme-preview em {
  position: absolute;
  left: 34%;
  top: 30px;
  width: 57%;
  height: 22px;
  border-radius: 5px;
}

.theme-preview u {
  position: absolute;
  left: 80%;
  top: 12px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.theme-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 6px;
  margin-top: 9px;
}

.theme-meta b {
  font-size: 11.5px;
}

.theme-meta > span {
  color: var(--app-muted);
  font-size: 9.5px;
}

.cb-badge,
.current-badge {
  margin-left: auto;
  padding: 2px 5px;
  border-radius: 4px;
  font-size: 8.5px;
}

.cb-badge {
  background: rgba(var(--app-info-rgb), 0.12);
  color: var(--app-info);
}

.current-badge {
  background: rgba(var(--app-primary-rgb), 0.12);
  color: var(--app-primary);
}

@media (max-width: 680px) {
  .theme-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
