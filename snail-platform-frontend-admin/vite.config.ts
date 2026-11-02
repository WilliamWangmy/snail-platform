import { defineConfig, loadEnv, type Plugin } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

/**
 * 构建期向 index.html 注入 CSP meta:全站此前没有任何内容安全策略,
 * meta 覆盖 frame-ancestors 不生效,真正的响应头(frame-ancestors 等)仍应由网关下发,
 * 这里是应用自身的第一道兜底。dev 模式不注入——HMR 依赖 inline/eval,注入会打断热更新。
 */
function cspMeta(): Plugin {
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
          + "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
          + "img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; "
          + "object-src 'none'; base-uri 'self'; form-action 'self'\">",
        );
      },
    },
  };
}

export default defineConfig(({ mode, command }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  // 构建期守门:口令回传解密依赖 VITE_PASSWORD_TRANSFER_KEY,缺钥的产物没有存在价值——
  // 与其产出一个"运行到结果弹窗才报解密失败"的包,不如在 CI 当场失败。
  if (command === 'build' && !env.VITE_PASSWORD_TRANSFER_KEY) {
    throw new Error(
      'VITE_PASSWORD_TRANSFER_KEY 未注入,拒绝构建。'
      + '请在构建环境或 .env.production 中提供,与后端 snail.security.password-transfer.key 同值。',
    );
  }
  return {
    plugins: [vue(), cspMeta()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 8000,
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
  };
});
