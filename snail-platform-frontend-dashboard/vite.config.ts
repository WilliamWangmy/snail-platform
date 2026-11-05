import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';
import { viteStaticCopy } from 'vite-plugin-static-copy';

// Cesium 运行时所需静态资源（Workers / Assets / Widgets / ThirdParty）。
// 注意：vite-plugin-static-copy 会保留 src 的相对目录结构，
// 因此产物位于 dist/cesium-assets/node_modules/cesium/Build/Cesium/ 下，
// index.html 中的 window.CESIUM_BASE_URL 需与之保持一致。
const cesiumSource = 'node_modules/cesium/Build/Cesium';

export default defineConfig({
  plugins: [
    vue(),
    viteStaticCopy({
      targets: [
        { src: `${cesiumSource}/Workers`, dest: 'cesium-assets' },
        { src: `${cesiumSource}/ThirdParty`, dest: 'cesium-assets' },
        { src: `${cesiumSource}/Assets`, dest: 'cesium-assets' },
        { src: `${cesiumSource}/Widgets`, dest: 'cesium-assets' },
      ],
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  define: {
    // Cesium 内部代码依赖 process.env.BASE_URL
    'process.env.BASE_URL': JSON.stringify('/'),
  },
  server: {
    port: 8001,
    strictPort: true,
    proxy: {
      // 认证接口与平台后端同源口径(/system/auth/*),经代理转发避免跨域;
      // 目标为后端 start 模块端口,与管理端 vite 代理一致。
      '/system': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
  },
});
