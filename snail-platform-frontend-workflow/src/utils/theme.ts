export type FlowTheme = 'dark' | 'light' | 'mist-light' | 'obsidian-dark' | 'cb-light' | 'cb-dark';

export interface FlowThemePreview {
  /** 缩略图：页面底色 */
  bg: string;
  /** 缩略图：边框线 */
  line: string;
  /** 缩略图：左侧深色条（对应 admin 侧栏位） */
  sidebar: string;
  /** 缩略图：主色条 */
  primary: string;
  /** 缩略图：内容卡色块 */
  card: string;
}

export interface FlowThemeMeta {
  id: FlowTheme;
  name: string;
  desc: string;
  mode: 'light' | 'dark';
  /** 色盲友好主题在面板中单独标记。 */
  cb: boolean;
  preview: FlowThemePreview;
}

/** 六套主题：晨航青/夜航青为控制台原生配色，其余四套取自管理端（snail-platform-frontend-admin stores/theme.ts）；preview 色值与管理端 THEMES 一致。 */
export const FLOW_THEMES: FlowThemeMeta[] = [
  {
    id: 'light', name: '晨航青', desc: '浅色', mode: 'light', cb: false,
    preview: { bg: '#eef4f3', line: '#dee3e3', sidebar: '#071820', primary: '#0da284', card: '#ffffff' },
  },
  {
    id: 'dark', name: '夜航青', desc: '深色', mode: 'dark', cb: false,
    preview: { bg: '#050f14', line: '#1d363f', sidebar: '#071820', primary: '#1ec9a5', card: '#0b202a' },
  },
  {
    id: 'mist-light', name: '雾松青', desc: '浅色 · 默认', mode: 'light', cb: false,
    preview: { bg: '#f3f7f7', line: '#e5eeed', sidebar: '#0a302c', primary: '#0f9f82', card: '#ffffff' },
  },
  {
    id: 'obsidian-dark', name: '曜石黑', desc: '深色 · 管理端', mode: 'dark', cb: false,
    preview: { bg: '#07110f', line: '#1b322e', sidebar: '#08211e', primary: '#35d6b5', card: '#0b1b19' },
  },
  {
    id: 'cb-light', name: '海盐蓝', desc: '浅色 · 色盲友好', mode: 'light', cb: true,
    preview: { bg: '#f3f6fb', line: '#dfe7f2', sidebar: '#0a2140', primary: '#2563c9', card: '#ffffff' },
  },
  {
    id: 'cb-dark', name: '深海蓝', desc: '深色 · 色盲友好', mode: 'dark', cb: true,
    preview: { bg: '#060d19', line: '#1b304b', sidebar: '#081426', primary: '#7cb8ff', card: '#0a1728' },
  },
];

const STORAGE_KEY = 'snail-flow-theme';
/** 管理端（snail-platform-frontend-admin）的主题键;同源部署时共用 localStorage,两端主题保持一致。 */
const ADMIN_STORAGE_KEY = 'snail_admin_theme_v1';

/** 流程端主题 id → 管理端主题 id(两套 id 命名不同,色板一一对应)。 */
const FLOW_TO_ADMIN: Record<FlowTheme, string> = {
  light: 'flow-light',
  dark: 'flow-dark',
  'mist-light': 'light',
  'obsidian-dark': 'dark',
  'cb-light': 'cb-light',
  'cb-dark': 'cb-dark',
};

/** 管理端主题 id → 流程端主题 id,上表反转。 */
const ADMIN_TO_FLOW: Record<string, FlowTheme> = Object.fromEntries(
  Object.entries(FLOW_TO_ADMIN).map(([flowId, adminId]) => [adminId, flowId as FlowTheme]),
) as Record<string, FlowTheme>;

function isFlowTheme(value: unknown): value is FlowTheme {
  return typeof value === 'string' && FLOW_THEMES.some((item) => item.id === value);
}

export function themeMeta(theme: FlowTheme): FlowThemeMeta {
  return FLOW_THEMES.find((item) => item.id === theme) ?? FLOW_THEMES[0]!;
}

export function currentTheme(): FlowTheme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (isFlowTheme(stored)) return stored;
    /* 管理端已选过主题:按映射回读,单点到流程时与管理端观感一致。 */
    const adminStored = localStorage.getItem(ADMIN_STORAGE_KEY);
    if (adminStored) {
      const mapped = ADMIN_TO_FLOW[adminStored];
      if (mapped && isFlowTheme(mapped)) return mapped;
    }
  } catch {
    /* ignore */
  }
  /* 缺省与管理端默认(雾松青)对齐,两端首访观感一致。 */
  return 'mist-light';
}

/** 模式类驱动明暗（html.dark/html.light），data-flow-theme 驱动四套扩展配色。 */
export function applyTheme(theme: FlowTheme): void {
  const meta = themeMeta(theme);
  const root = document.documentElement;
  root.classList.toggle('dark', meta.mode === 'dark');
  root.classList.toggle('light', meta.mode === 'light');
  root.dataset.flowTheme = meta.id;
  try {
    localStorage.setItem(STORAGE_KEY, meta.id);
    /* 双键同步:管理端同源打开时读到同一偏好。 */
    localStorage.setItem(ADMIN_STORAGE_KEY, FLOW_TO_ADMIN[theme]);
  } catch {
    /* ignore */
  }
}

export function initTheme(): FlowTheme {
  const theme = currentTheme();
  applyTheme(theme);
  return theme;
}

export function setTheme(theme: FlowTheme): FlowTheme {
  applyTheme(theme);
  return theme;
}
