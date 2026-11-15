/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ADMIN_URL?: string;
  readonly VITE_SCREEN_URL?: string;
  readonly VITE_MONITOR_ARTHAS_ORIGIN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
