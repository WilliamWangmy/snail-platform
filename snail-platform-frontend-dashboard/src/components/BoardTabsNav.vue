<script setup lang="ts">
import type { BoardTab } from '@/config/boards';

/** 斜切梯形 tab 组:左右两翼共用同一份几何,高亮与「建设中」判定由父级持有。 */
defineProps<{
  tabs: BoardTab[];
  active: BoardTab['key'];
  label: string;
}>();

const emit = defineEmits<{ select: [tab: BoardTab] }>();
</script>

<template>
  <nav
    class="ck-tabs"
    :aria-label="label"
  >
    <button
      v-for="tab in tabs"
      :key="tab.key"
      type="button"
      class="ck-tab"
      :title="tab.label"
      :aria-label="tab.label"
      :class="{ active: active === tab.key }"
      :aria-current="active === tab.key ? 'page' : undefined"
      :aria-disabled="tab.built ? undefined : 'true'"
      @click="emit('select', tab)"
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
        <template v-if="tab.key === 'flight'">
          <circle
            cx="12"
            cy="12"
            r="9"
          />
          <path d="M12 3v18M3 12h18" />
          <circle
            cx="15.5"
            cy="8.5"
            r="1.6"
          />
        </template>
        <template v-else-if="tab.key === 'flow'">
          <circle
            cx="6"
            cy="6"
            r="2.4"
          />
          <circle
            cx="18"
            cy="6"
            r="2.4"
          />
          <circle
            cx="12"
            cy="18"
            r="2.4"
          />
          <path d="M7.4 8.1l3.5 8M16.6 8.1l-3.5 8" />
        </template>
        <template v-else-if="tab.key === 'admin'">
          <rect
            x="3"
            y="3"
            width="7.5"
            height="7.5"
            rx="1.5"
          />
          <rect
            x="13.5"
            y="3"
            width="7.5"
            height="7.5"
            rx="1.5"
          />
          <rect
            x="3"
            y="13.5"
            width="7.5"
            height="7.5"
            rx="1.5"
          />
          <rect
            x="13.5"
            y="13.5"
            width="7.5"
            height="7.5"
            rx="1.5"
          />
        </template>
        <template v-else>
          <rect
            x="6.5"
            y="6.5"
            width="11"
            height="11"
            rx="2"
          />
          <path d="M10 3v3.5M14 3v3.5M10 17.5V21M14 17.5V21M3 10h3.5M3 14h3.5M17.5 10H21M17.5 14H21" />
        </template>
      </svg>
      <span class="tab-label">{{ tab.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.ck-tabs {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
}

.ck-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 16px;
  clip-path: polygon(9px 0, 100% 0, calc(100% - 9px) 100%, 0 100%);
  background: rgba(14, 32, 58, 0.7);
  color: #94a3b8;
  font-size: 12.5px;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background-color 0.18s ease, color 0.18s ease;
}

.ck-tab svg {
  width: 14px;
  height: 14px;
}

.ck-tab:hover {
  background: rgba(8, 51, 68, 0.85);
  color: #bae6fd;
}

.ck-tab.active {
  background: linear-gradient(180deg, rgba(34, 211, 238, 0.34), rgba(8, 51, 68, 0.6));
  color: #e0f2fe;
}

.ck-tab.active::after {
  content: '';
  position: absolute;
  right: 20%;
  bottom: 3px;
  left: 20%;
  height: 2px;
  background: #22d3ee;
  box-shadow: 0 0 10px rgba(34, 211, 238, 0.9);
}

/* clip-path 会裁掉 outline,焦点态改用整块底色提亮 */
.ck-tab:focus-visible {
  background: rgba(34, 211, 238, 0.28);
  color: #ecfeff;
}

@media (max-width: 1500px) {
  .ck-tab {
    padding: 0 12px;
  }
}

@media (max-width: 1180px) {
  .ck-tab {
    padding: 0 10px;
  }

  .tab-label {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ck-tab {
    transition: none;
  }
}
</style>
