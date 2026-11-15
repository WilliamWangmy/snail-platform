import type { Directive } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * v-permission="'platform:monitor:overview'" 或 v-permission="['a','b']"(任一命中)。
 * 无权限即隐藏节点,与后端 @PreAuthorize 同一套权限码;display 隐藏而非 remove,
 * updated 钩子能在权限变更后重评恢复;越权请求仍由后端 403 最终裁决。
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    el.dataset.permDisplay = el.style.display || '';
    apply(el, binding);
  },
  updated(el, binding) {
    apply(el, binding);
  },
};

function apply(el: HTMLElement, binding: { value?: string | string[] }): void {
  const allowed = useAuthStore().has(binding.value ?? []);
  const original = el.dataset.permDisplay ?? '';
  el.style.display = allowed ? original : 'none';
}
