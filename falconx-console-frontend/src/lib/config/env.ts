/** 环境变量 (Vite 运行时通过 import.meta.env 注入)。 */
export const ConsoleEnv = {
  /** 管理端 API 基础 URL (开发环境 vite proxy /admin → console-service :18085)。 */
  apiBaseUrl: import.meta.env.VITE_FALCONX_CONSOLE_API_BASE_URL ?? "",
  /**
   * 管理端 WebSocket 基础 URL。
   *
   * STAGE-2-REALTIME-DATA Phase 4：connect 到 gateway /ws/v1/trading?token=<admin-token>，
   * gateway 按 token iss 字段分流到 trading-core admin session。
   * 开发环境直连 gateway:18080；生产环境同源访问。
   */
  wsBaseUrl: import.meta.env.VITE_FALCONX_CONSOLE_WS_BASE_URL ?? resolveDefaultWsBaseUrl(),
} as const;

function resolveDefaultWsBaseUrl(): string {
  if (typeof window === "undefined") {
    return "ws://localhost:18080";
  }
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  // dev：vite (5300) 上加了 /ws 代理转发到 gateway:18080，所以同源访问即可
  // 旧实现直连 :18080 在跨机访问时被 WSL2 / firewall 隔离（其他局域网机器看不到 18080）
  return `${protocol}//${window.location.host}`;
}
