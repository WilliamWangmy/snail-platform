<script setup lang="ts">
import { computed } from 'vue';
import type { MenuRoute } from '@/api/menu';
import AppIcon from '@/components/AppIcon.vue';
import { menuIcon } from '@/utils/icons';

const props = defineProps<{ nodes: MenuRoute[] }>();

/** visible=1 的节点只在管理页可见,不进导航。 */
const visibleNodes = computed(() => props.nodes.filter((node) => node.visible === 0));

/** 目录要剩下可见子项才成导航:后端 prune 只数有没有子,看不见的那层只有界面知道。 */
function childrenOf(node: MenuRoute): MenuRoute[] {
  return (node.children ?? []).filter((child) => child.visible === 0);
}
</script>

<template>
  <template
    v-for="node in visibleNodes"
    :key="node.menuId"
  >
    <ElSubMenu
      v-if="node.menuType === 1 && childrenOf(node).length"
      :index="`dir-${node.menuId}`"
    >
      <template #title>
        <AppIcon
          :name="menuIcon(node.icon)"
          :size="18"
        />
        <span>{{ node.menuName }}</span>
      </template>
      <SidebarMenu :nodes="node.children" />
    </ElSubMenu>

    <!-- 外链不建路由,index 即完整 URL,由布局拦截后新标签打开 -->
    <ElMenuItem
      v-else-if="node.target === 3 && node.path"
      :index="node.path"
    >
      <AppIcon
        name="external"
        :size="18"
      />
      <span>{{ node.menuName }}</span>
    </ElMenuItem>

    <ElMenuItem
      v-else-if="node.menuType === 2 && node.path"
      :index="node.path"
    >
      <AppIcon
        :name="menuIcon(node.icon)"
        :size="18"
      />
      <span>{{ node.menuName }}</span>
    </ElMenuItem>
  </template>
</template>
