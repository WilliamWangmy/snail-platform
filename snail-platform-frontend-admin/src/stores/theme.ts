import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

export interface ThemeTokens {
  id: string;
  name: string;
  desc: string;
  mode: 'light' | 'dark';
  primary: string;
  accent: string;
  bg: string;
  card: string;
  soft: string;
  soft2: string;
  ink: string;
  muted: string;
  line: string;
  sidebar: string;
  sidebar2: string;
  sidebarText: string;
  sidebarMuted: string;
  onPrimary: string;
  success: string;
  warning: string;
  danger: string;
  info: string;
  chart: string[];
  shadow: string;
  cb: boolean;
}

/** 四套主题:浅色/深色各两列,色盲友好版以蓝橙紫青替代红绿并保留文字标记。 */
export const THEMES: ThemeTokens[] = [
  {
    id: 'light', name: '雾松青', desc: '浅色 · 默认', mode: 'light',
    primary: '#0f9f82', accent: '#e0a14d', bg: '#f3f7f7', card: '#ffffff', soft: '#f7fbfa', soft2: '#eef5f4',
    ink: '#17302f', muted: '#728583', line: '#e5eeed', sidebar: '#0a302c', sidebar2: '#072421',
    sidebarText: 'rgba(224,255,248,.62)', sidebarMuted: 'rgba(197,255,243,.38)', onPrimary: '#ffffff',
    success: '#0d977d', warning: '#b77a29', danger: '#dc655f', info: '#4b7fa9',
    chart: ['#0fa78b', '#59cbb6', '#eab15c', '#8aa7c4'], shadow: '0 14px 38px rgba(16,61,57,.07)', cb: false,
  },
  {
    id: 'dark', name: '曜石黑', desc: '深色 · 夜间', mode: 'dark',
    primary: '#35d6b5', accent: '#f4b45f', bg: '#07110f', card: '#0b1b19', soft: '#0d211e', soft2: '#102622',
    ink: '#e7f5f2', muted: '#8ea6a2', line: '#1b322e', sidebar: '#08211e', sidebar2: '#061714',
    sidebarText: 'rgba(224,255,248,.65)', sidebarMuted: 'rgba(197,255,243,.4)', onPrimary: '#04211c',
    success: '#35d6b5', warning: '#f4b45f', danger: '#ff8d86', info: '#8bbde6',
    chart: ['#37d6b6', '#67e3c9', '#f4b45f', '#7ca7c9'], shadow: '0 18px 44px rgba(0,0,0,.34)', cb: false,
  },
  {
    id: 'cb-light', name: '海盐蓝', desc: '浅色 · 色盲友好', mode: 'light',
    primary: '#2563c9', accent: '#d97706', bg: '#f3f6fb', card: '#ffffff', soft: '#f7f9fd', soft2: '#eef3fa',
    ink: '#182842', muted: '#62748c', line: '#dfe7f2', sidebar: '#0a2140', sidebar2: '#061326',
    sidebarText: 'rgba(224,238,255,.66)', sidebarMuted: 'rgba(184,215,255,.42)', onPrimary: '#ffffff',
    success: '#2563c9', warning: '#b45309', danger: '#b45309', info: '#7c3aed',
    chart: ['#2563c9', '#e0821c', '#7c3aed', '#0891b2'], shadow: '0 14px 38px rgba(37,73,128,.08)', cb: true,
  },
  {
    id: 'cb-dark', name: '深海蓝', desc: '深色 · 色盲友好', mode: 'dark',
    primary: '#7cb8ff', accent: '#ffb454', bg: '#060d19', card: '#0a1728', soft: '#0d1d32', soft2: '#10233b',
    ink: '#eaf3ff', muted: '#92a8c4', line: '#1b304b', sidebar: '#081426', sidebar2: '#040b16',
    sidebarText: 'rgba(224,238,255,.68)', sidebarMuted: 'rgba(184,215,255,.44)', onPrimary: '#06182f',
    success: '#7cb8ff', warning: '#ffb454', danger: '#ffb454', info: '#b49bff',
    chart: ['#6fb3ff', '#ffb454', '#b49bff', '#5be2e8'], shadow: '0 18px 44px rgba(0,0,0,.38)', cb: true,
  },
];

const THEME_KEY = 'snail_admin_theme_v1';

function readStoredTheme(): string {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    return stored && THEMES.some((item) => item.id === stored) ? stored : 'light';
  } catch {
    return 'light';
  }
}

function hexToRgb(hex: string): number[] {
  const value = hex.replace('#', '');
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value;
  return [parseInt(full.slice(0, 2), 16), parseInt(full.slice(2, 4), 16), parseInt(full.slice(4, 6), 16)];
}

function mixColor(from: string, to: string, ratio: number): string {
  const a = hexToRgb(from);
  const b = hexToRgb(to);
  return `#${a
    .map((channel, index) => Math.round(channel + ((b[index] ?? channel) - channel) * ratio))
    .map((channel) => channel.toString(16).padStart(2, '0'))
    .join('')}`;
}

/** 同时写 --app-* 与 Element Plus 变量,组件库与自绘区域共用一套色板。 */
function applyTokens(theme: ThemeTokens): void {
  const root = document.documentElement;
  const vars: Record<string, string> = {
    '--app-primary': theme.primary,
    '--app-accent': theme.accent,
    '--app-bg': theme.bg,
    '--app-card': theme.card,
    '--app-soft': theme.soft,
    '--app-soft-2': theme.soft2,
    '--app-ink': theme.ink,
    '--app-muted': theme.muted,
    '--app-line': theme.line,
    '--app-sidebar': theme.sidebar,
    '--app-sidebar-2': theme.sidebar2,
    '--app-sidebar-text': theme.sidebarText,
    '--app-sidebar-muted': theme.sidebarMuted,
    '--app-on-primary': theme.onPrimary,
    '--app-success': theme.success,
    '--app-warning': theme.warning,
    '--app-danger': theme.danger,
    '--app-info': theme.info,
    '--app-chart-1': theme.chart[0] ?? theme.primary,
    '--app-chart-2': theme.chart[1] ?? theme.primary,
    '--app-chart-3': theme.chart[2] ?? theme.accent,
    '--app-chart-4': theme.chart[3] ?? theme.info,
    '--app-shadow': theme.shadow,
    '--el-color-primary': theme.primary,
    '--el-color-primary-dark-2': mixColor(theme.primary, '#000000', 0.2),
    '--el-color-success': theme.success,
    '--el-color-warning': theme.warning,
    '--el-color-danger': theme.danger,
    '--el-color-error': theme.danger,
    '--el-color-info': theme.info,
    '--el-bg-color': theme.card,
    '--el-bg-color-page': theme.bg,
    '--el-bg-color-overlay': theme.card,
    '--el-text-color-primary': theme.ink,
    '--el-text-color-regular': theme.muted,
    '--el-text-color-secondary': theme.muted,
    '--el-text-color-placeholder': mixColor(theme.muted, theme.bg, 0.35),
    '--el-border-color': theme.line,
    '--el-border-color-light': theme.line,
    '--el-border-color-lighter': theme.line,
    '--el-border-color-extra-light': theme.line,
    '--el-fill-color': theme.soft2,
    '--el-fill-color-light': theme.soft,
    '--el-fill-color-lighter': theme.soft,
    '--el-fill-color-blank': theme.card,
    '--el-fill-color-dark': theme.soft2,
    '--el-fill-color-darker': theme.soft2,
    '--el-card-bg-color': theme.card,
    '--el-dialog-bg-color': theme.card,
    '--el-drawer-bg-color': theme.card,
    '--el-popover-bg-color': theme.card,
    '--el-mask-color': theme.mode === 'dark' ? 'rgba(0,0,0,.62)' : 'rgba(20,45,42,.42)',
    '--el-table-bg-color': theme.card,
    '--el-table-tr-bg-color': theme.card,
    '--el-table-header-bg-color': theme.soft,
    '--el-table-border-color': theme.line,
    '--el-table-text-color': theme.muted,
    '--el-table-header-text-color': theme.muted,
    '--el-table-row-hover-bg-color': theme.soft,
    '--el-menu-active-color': theme.primary,
    '--el-menu-text-color': theme.sidebarText,
    '--el-menu-hover-text-color': '#ffffff',
    '--el-menu-bg-color': 'transparent',
    '--el-menu-hover-bg-color': 'rgba(255,255,255,.06)',
  };
  const mixBase = theme.mode === 'dark' ? theme.card : '#ffffff';
  for (const level of [3, 5, 7, 8, 9]) {
    vars[`--el-color-primary-light-${level}`] = mixColor(theme.primary, mixBase, level / 10);
    vars[`--el-color-success-light-${level}`] = mixColor(theme.success, mixBase, level / 10);
    vars[`--el-color-warning-light-${level}`] = mixColor(theme.warning, mixBase, level / 10);
    vars[`--el-color-danger-light-${level}`] = mixColor(theme.danger, mixBase, level / 10);
    vars[`--el-color-error-light-${level}`] = mixColor(theme.danger, mixBase, level / 10);
  }
  /* 自绘区域用 RGB 通道令牌叠透明度,避免依赖 color-mix(老内核整条声明会失效)。 */
  vars['--app-primary-rgb'] = hexToRgb(theme.primary).join(', ');
  vars['--app-accent-rgb'] = hexToRgb(theme.accent).join(', ');
  vars['--app-card-rgb'] = hexToRgb(theme.card).join(', ');
  vars['--app-success-rgb'] = hexToRgb(theme.success).join(', ');
  vars['--app-warning-rgb'] = hexToRgb(theme.warning).join(', ');
  vars['--app-danger-rgb'] = hexToRgb(theme.danger).join(', ');
  vars['--app-info-rgb'] = hexToRgb(theme.info).join(', ');
  vars['--app-primary-tint'] = mixColor(theme.primary, '#ffffff', 0.45);
  vars['--app-sidebar-glow'] = mixColor(theme.sidebar, theme.primary, 0.16);
  root.dataset.theme = theme.id;
  root.classList.toggle('dark', theme.mode === 'dark');
  root.style.colorScheme = theme.mode;
  for (const [key, value] of Object.entries(vars)) root.style.setProperty(key, value);
}

/** 主题状态:模块加载即按已存偏好着色,不等应用挂载,避免首屏闪默认色。 */
const initialId = readStoredTheme();
applyTokens(THEMES.find((item) => item.id === initialId) ?? THEMES[0]!);

export const useThemeStore = defineStore('theme', () => {
  const currentId = ref(initialId);
  const current = computed(() => THEMES.find((item) => item.id === currentId.value) ?? THEMES[0]!);

  function select(id: string): void {
    if (!THEMES.some((item) => item.id === id)) return;
    currentId.value = id;
    try {
      localStorage.setItem(THEME_KEY, id);
    } catch {
      /* ignore */
    }
    applyTokens(current.value);
  }

  return { currentId, current, themes: THEMES, select };
});
