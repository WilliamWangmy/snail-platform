<script lang="ts">
export interface RowAction {
  key: string;
  /** 文案由调用方算好,停用/启用这类随行状态变化。 */
  label: string;
  /** 危险操作在按钮与下拉项里统一用警示色。 */
  tone?: 'primary' | 'danger';
  /** 权限不足或当前行不允许时置 false,不占展示位。 */
  show?: boolean;
  handler: () => void;
}
</script>

<script setup lang="ts">
import { computed } from 'vue';
import AppIcon from '@/components/AppIcon.vue';

const props = defineProps<{ actions: RowAction[] }>();

/** 可见操作超过三个时,前两个常驻、其余收进「更多」,操作列最多三个按钮。 */
const visible = computed(() => props.actions.filter((item) => item.show !== false));
const folded = computed(() => visible.value.length > 3);
const inline = computed(() => (folded.value ? visible.value.slice(0, 2) : visible.value));
const overflow = computed(() => (folded.value ? visible.value.slice(2) : []));

function run(key: string | number | object): void {
  overflow.value.find((item) => item.key === key)?.handler();
}
</script>

<template>
  <div class="row-actions">
    <ElButton
      v-for="action in inline"
      :key="action.key"
      link
      :type="action.tone ?? 'primary'"
      @click="action.handler"
    >
      {{ action.label }}
    </ElButton>
    <ElDropdown
      v-if="overflow.length"
      trigger="click"
      @command="run"
    >
      <ElButton
        link
        type="primary"
        class="row-actions-more"
      >
        更多
        <AppIcon
          name="chevron-down"
          :size="12"
        />
      </ElButton>
      <template #dropdown>
        <ElDropdownMenu>
          <ElDropdownItem
            v-for="action in overflow"
            :key="action.key"
            :command="action.key"
          >
            <span :class="{ 'row-action-danger': action.tone === 'danger' }">{{ action.label }}</span>
          </ElDropdownItem>
        </ElDropdownMenu>
      </template>
    </ElDropdown>
  </div>
</template>

<style scoped>
.row-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.row-actions :deep(.el-button) {
  margin-left: 0;
}

.row-actions :deep(.app-icon) {
  margin-left: 2px;
}

.row-action-danger {
  color: var(--app-danger);
}
</style>
