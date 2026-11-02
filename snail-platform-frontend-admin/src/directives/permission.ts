import type { Directive } from 'vue';
import { useAuthStore } from '@/stores/auth';

/**
 * v-permission="'system:user:create'" 或 v-permission="['a','b']"(任一命中)。
 * 无权限即隐藏节点,与后端 @PreAuthorize 同一套权限码,不做第二套前端判定。
 * 用 display 隐藏而非 remove:登录后权限变更(角色调整/被收权)时 updated 钩子能重评并恢复,
 * remove 摘掉的节点在会话期内无法复活;隐藏只是 UX 装饰,越权请求仍由后端 403 最终裁决。
 */
export const permission: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    // 记住原始 display,恢复时不覆盖组件自带的行内样式
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
