import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [uni()],
  server: {
    // 移动端 H5 开发服务器固定端口
    port: 8003,
    host: "0.0.0.0",
    proxy: {
      // H5 开发期同源转发后端,避免浏览器跨域;小程序/App 无代理,仍直连 VITE_API_BASE_URL
      "/system": {
        target: "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 8003,
    host: "0.0.0.0",
  },
});
