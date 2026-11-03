export type FlowTheme = 'dark' | 'light';

const STORAGE_KEY = 'snail-flow-theme';

export function currentTheme(): FlowTheme {
  return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
}

export function applyTheme(theme: FlowTheme): void {
  const root = document.documentElement;
  root.classList.toggle('dark', theme === 'dark');
  root.classList.toggle('light', theme === 'light');
  localStorage.setItem(STORAGE_KEY, theme);
}

export function initTheme(): FlowTheme {
  const theme = currentTheme();
  applyTheme(theme);
  return theme;
}

export function toggleTheme(): FlowTheme {
  const theme = currentTheme() === 'dark' ? 'light' : 'dark';
  applyTheme(theme);
  return theme;
}
