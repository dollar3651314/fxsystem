function resolveDefaultWsBaseUrl() {
  if (typeof window === "undefined") {
    return "ws://localhost:18080";
  }

  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}`;
}

export const apiBaseUrl = import.meta.env.VITE_FALCONX_API_BASE_URL ?? "";

export const wsBaseUrl =
  import.meta.env.VITE_FALCONX_WS_BASE_URL ?? resolveDefaultWsBaseUrl();

/**
 * 区块链浏览器 base URL，按链路由。一期只接 ETH（Sepolia 测试期）+ TRON。
 * 上 mainnet 时切到 etherscan.io / tronscan.org（去掉 sepolia / nile）即可。
 * 通过 VITE_FALCONX_EXPLORER_ETH 等 env 可覆盖。
 */
export const explorerBaseUrls = {
  ETH: import.meta.env.VITE_FALCONX_EXPLORER_ETH ?? "https://sepolia.etherscan.io",
  TRON: import.meta.env.VITE_FALCONX_EXPLORER_TRON ?? "https://nile.tronscan.org/#",
} as const;

/**
 * 由 txHash 推断浏览器链接。优先用 hash 形态：
 * - 0x 前缀 64 hex → EVM 链（ETH / BSC，当前一期只接 ETH）
 * - base58 32+ 字符 → TRON
 * 返回 null 表示不识别（不显示跳转链接）。
 */
export function buildTxExplorerUrl(txHash: string | null | undefined): string | null {
  if (!txHash) return null;
  const hash = txHash.trim();
  if (/^0x[0-9a-fA-F]{64}$/.test(hash)) {
    return `${explorerBaseUrls.ETH}/tx/${hash}`;
  }
  if (/^[A-Za-z0-9]{40,}$/.test(hash) && !hash.startsWith("0x")) {
    return `${explorerBaseUrls.TRON}/transaction/${hash}`;
  }
  return null;
}
