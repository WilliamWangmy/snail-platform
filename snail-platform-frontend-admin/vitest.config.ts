import { fileURLToPath, URL } from 'node:url';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vitest/config';

/**
 * 单测独立配置:不带 vite.config 的构建守门与环境依赖,只保留 @ 别名与 jsdom 环境。
 * 组件级用例挂 @vue/test-utils,纯函数用例(node 环境)走同目录同后缀约定。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
  },
});
