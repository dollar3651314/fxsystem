import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5200,
    proxy: {
      "/api": {
        target: "http://localhost:18080",
        changeOrigin: true
      },
      "/ws": {
        target: "ws://localhost:18080",
        changeOrigin: true,
        ws: true
      }
    }
  },
  build: {
    // STAGE-12 perf：默认 Vite 把所有 deps + 应用代码打成单 720KB chunk，
    // 冷启动需下载完整 bundle 才能渲染首屏。手动拆分 vendor chunk 让浏览器并行下载
    // + 长期缓存 vendor（库版本不动时不重新下载）。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) return undefined;
          // React 核心（每页都需，单独 chunk 长期缓存）
          if (id.includes("/react/") || id.includes("/react-dom/")) {
            return "vendor-react";
          }
          // TradingView 图表（最重的库 ~250KB，只在 K 线页用）
          if (id.includes("lightweight-charts")) return "vendor-chart";
          // 状态/网络/动画/icons（中等体量库聚合）
          if (
            id.includes("@tanstack/react-query") ||
            id.includes("/zustand/") ||
            id.includes("/framer-motion/") ||
            id.includes("/lucide-react/")
          ) {
            return "vendor-runtime";
          }
          return undefined;
        }
      }
    },
    // 单 chunk 上限提到 600KB，超过会警告
    chunkSizeWarningLimit: 600
  }
});
