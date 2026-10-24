import type { Directive } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * v-permission="'system:user:create'" 或 v-permission="['a','b']"(任一命中)。
 * 无权限即从 DOM 摘掉节点,与后端 @PreAuthorize 同一套权限码,不做第二套前端判定。
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    if (!useAuthStore().has(binding.value ?? [])) el.remove();
  },
};
