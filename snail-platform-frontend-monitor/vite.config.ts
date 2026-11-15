import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig, type Plugin } from 'vite';

/**
 * 构建期向 index.html 注入 CSP meta(照 admin 同式),追加 frame-src 白名单:
 * Arthas tunnel 在线诊断以 iframe 嵌入,构建期以 VITE_MONITOR_ARTHAS_ORIGIN 放行其 origin;
 * 未配置时仅 'self'(iframe 被拦,页面有提示引导补配置后重建)。
 */
function cspMeta(): Plugin {
  const arthasOrigin = process.env.VITE_MONITOR_ARTHAS_ORIGIN ?? '';
  const frameSrc = arthasOrigin ? `frame-src 'self' ${arthasOrigin}; ` : "frame-src 'self'; ";
  return {
    name: 'inject-csp-meta',
    apply: 'build',
    transformIndexHtml: {
      order: 'pre',
      handler(html) {
        return html.replace(
          '<head>',
          '<head>\n'
          + '    <meta http-equiv="Content-Security-Policy" content="'
          + "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline' data:; "
          + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; "
          + frameSrc
          + "object-src 'none'; base-uri 'self'; form-action 'self'\">",
        );
      },
    },
  };
}

export default defineConfig(() => {
  return {
    plugins: [vue(), cspMeta()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 8003,
      strictPort: true,
      proxy: {
        '/system': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          // API 前缀与菜单 path(权限树载体)同为 /monitor/system:导航交给 SPA,只有 XHR 转后端
          bypass: (req) => (req.headers.accept?.includes('text/html') ? '/index.html' : undefined),
        },
        '/monitor': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          bypass: (req) => (req.headers.accept?.includes('text/html') ? '/index.html' : undefined),
        },
      },
    },
    build: {
      target: 'es2022',
      outDir: 'dist',
    },
  };
});
