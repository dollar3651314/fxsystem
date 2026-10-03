import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// 管理端独立部署 (按管理端架构 §1.1 端口 5300)
// /admin/* 既是前端 SPA 路由 (/admin/login 等)，又是后端 API 前缀 (/admin/auth/* 等)。
// 开发环境通过 bypass 区分：
//   - 浏览器导航请求 (Accept: text/html) → vite 返回 index.html，让 React Router 接管
//   - XHR/fetch 请求 → proxy 到 console-service :18085
// 生产环境通过 gateway :18080 + path 路由解决（R4 单独实施）。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5300,
    proxy: {
      "/admin": {
        target: "http://localhost:18085",
        changeOrigin: true,
        bypass(req) {
          if (req.headers.accept?.includes("text/html")) {
            return "/index.html";
          }
        }
      },
      // STAGE-2-REALTIME-DATA Phase 4：admin WS 通过 vite 代理到 gateway:18080，
      // 避免跨机访问时浏览器直连 :18080 被 WSL2 / firewall 隔离
      "/ws": {
        target: "ws://localhost:18080",
        changeOrigin: true,
        ws: true
      }
    }
  }
});
