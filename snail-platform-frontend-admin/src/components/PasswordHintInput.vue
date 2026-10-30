<script setup lang="ts">
import { computed, ref } from 'vue';
import AppIcon from '@/components/AppIcon.vue';
import { passwordChecklist } from '@/utils/password';

/** 密码输入框通用形态:眼睛图标切换明文,聚焦才悬浮出强度与满足条件清单,失焦即收 */
const props = withDefaults(defineProps<{
  modelValue?: string;
  placeholder?: string;
  autocomplete?: string;
}>(), {
  modelValue: '',
  placeholder: '8-20 位，四类字符全含',
  autocomplete: 'new-password',
});
const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>();

const wrapRef = ref<HTMLElement>();
const hintVisible = ref(false);

const value = computed({
  get: () => props.modelValue,
  set: (next: string) => emit('update:modelValue', next),
});

const checklist = computed(() => {
  const c = passwordChecklist(props.modelValue);
  return [
    { label: '长度 8-20 位', ok: c.length },
    { label: '大写字母', ok: c.upper },
    { label: '小写字母', ok: c.lower },
    { label: '数字', ok: c.digit },
    { label: '特殊符号', ok: c.symbol },
    { label: '无空格 · 非常见口令', ok: c.noWhitespace && c.notBlacklisted },
  ];
});

const strength = computed(() => {
  const password = props.modelValue;
  if (!password) return { score: 0, label: '待输入' };
  const c = passwordChecklist(password);
  const classes = [c.upper, c.lower, c.digit, c.symbol].filter(Boolean).length;
  // 类别数 + 长度加分:满分 5,≤3 弱、4 中、5 强;黑名单口令本身类别少,天然落弱档
  const points = classes + (password.length >= 12 ? 1 : 0);
  const score = points <= 3 ? 1 : points === 4 ? 2 : 3;
  return { score, label: ['弱', '中', '强'][score - 1] };
});

/** 焦点仍在容器内(含可见性切换)则保持展示,落到容器外才收起 */
function onHintFocusOut(event: FocusEvent): void {
  if (!wrapRef.value?.contains(event.relatedTarget as Node | null)) {
    hintVisible.value = false;
  }
}
</script>

<template>
  <div
    ref="wrapRef"
    class="pwd-wrap"
    @focusin="hintVisible = true"
    @focusout="onHintFocusOut"
  >
    <ElInput
      v-model="value"
      type="password"
      show-password
      size="large"
      :autocomplete="autocomplete"
      :placeholder="placeholder"
    />
    <Transition name="pwd-hint">
      <div
        v-if="hintVisible"
        class="pwd-hint"
      >
        <div
          class="strength"
          :class="`l${strength.score}`"
        >
          <div class="strength-bars">
            <i :class="{ on: strength.score >= 1 }" />
            <i :class="{ on: strength.score >= 2 }" />
            <i :class="{ on: strength.score >= 3 }" />
          </div>
          <span class="strength-label">强度：{{ strength.label }}</span>
        </div>
        <ul class="checklist">
          <li
            v-for="item in checklist"
            :key="item.label"
            :class="{ ok: item.ok }"
          >
            <AppIcon
              :name="item.ok ? 'check' : 'close'"
              :size="11"
            />
            {{ item.label }}
          </li>
        </ul>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.pwd-wrap {
  position: relative;
  width: 100%;
}

.pwd-hint {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  z-index: 20;
  padding: 10px 12px;
  border: 1px solid var(--app-line);
  border-radius: 10px;
  background: var(--app-card);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.16);
}

.pwd-hint-enter-active,
.pwd-hint-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.pwd-hint-enter-from,
.pwd-hint-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .pwd-hint-enter-active,
  .pwd-hint-leave-active {
    transition: none;
  }
}

.strength {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
}

.strength-bars {
  display: flex;
  flex: 1;
  gap: 4px;
}

.strength-bars i {
  height: 4px;
  flex: 1;
  border-radius: 99px;
  background: var(--app-line);
  transition: background-color 0.2s;
}

.strength-label {
  flex: none;
  color: var(--app-muted);
  font-size: 11px;
  transition: color 0.2s;
}

.strength.l1 .strength-label {
  color: var(--app-danger);
}

.strength.l1 .strength-bars i.on {
  background: var(--app-danger);
}

.strength.l2 .strength-label {
  color: var(--app-warning);
}

.strength.l2 .strength-bars i.on {
  background: var(--app-warning);
}

.strength.l3 .strength-label {
  color: var(--app-success);
}

.strength.l3 .strength-bars i.on {
  background: var(--app-success);
}

.checklist {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 5px 14px;
  width: 100%;
  margin: 9px 0 0;
  padding: 0;
  list-style: none;
}

.checklist li {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--app-muted);
  font-size: 11.5px;
  transition: color 0.2s;
}

.checklist li.ok {
  color: var(--app-success);
}
</style>
