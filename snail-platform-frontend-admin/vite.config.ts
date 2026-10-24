import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/system': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 菜单表里的路由与接口同为 /system 前缀:浏览器地址栏直达页面要交给 SPA,
        // 只有 XHR(Accept 不含 text/html)才转给后端。
        bypass: (req) => (req.headers.accept?.includes('text/html') ? '/index.html' : undefined),
      },
    },
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
  },
});
