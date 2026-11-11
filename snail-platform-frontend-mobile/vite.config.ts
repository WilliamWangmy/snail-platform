import { defineConfig } from "vite";
import uni from "@dcloudio/vite-plugin-uni";

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [uni()],
  server: {
    // 移动端 H5 开发服务器固定端口
    port: 8003,
    host: "0.0.0.0",
  },
  preview: {
    port: 8003,
    host: "0.0.0.0",
  },
});
