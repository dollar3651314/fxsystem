/**
 * 区块链元数据 + 浏览器 URL 集中配置（admin 端）。
 *
 * <p>原来 DepositDetailDrawer 把 explorer URL 硬编码为 mainnet（etherscan.io），
 * 而 WithdrawDetailPage 又自己写了一份 testnet 版本，互相不一致。这里统一收口：
 *
 * <ul>
 *   <li>CHAIN_TO_NETWORK：链 → 业务网络代号（ETH→ERC20，TRON→TRC20）</li>
 *   <li>EXPLORER_TX_URL：链 → tx 浏览器链接构造器（默认 testnet）</li>
 *   <li>EXPLORER_NAME：链 → 浏览器友好名（按钮 / 行文显示用）</li>
 * </ul>
 *
 * <p>切 mainnet 时通过 .env 覆盖（不动代码）：
 * <pre>
 *   VITE_FALCONX_EXPLORER_ETH=https://etherscan.io
 *   VITE_FALCONX_EXPLORER_BSC=https://bscscan.com
 *   VITE_FALCONX_EXPLORER_TRON=https://tronscan.org/#
 *   VITE_FALCONX_EXPLORER_SOL=https://solscan.io
 * </pre>
 */
export type ChainType = "ETH" | "BSC" | "TRON" | "SOL";

/** 链类型对应的业务网络代号（与 wallet-service network 字段口径一致）。 */
export const CHAIN_TO_NETWORK: Record<ChainType, string> = {
  ETH: "ERC20",
  BSC: "BEP20",
  TRON: "TRC20",
  SOL: "SPL",
};

/** 浏览器 base URL，按链路由，env 可覆盖。默认值是测试网，避免 dev/UAT 复制粘贴打错网。 */
const EXPLORER_BASE: Record<ChainType, string> = {
  ETH: import.meta.env.VITE_FALCONX_EXPLORER_ETH ?? "https://sepolia.etherscan.io",
  BSC: import.meta.env.VITE_FALCONX_EXPLORER_BSC ?? "https://testnet.bscscan.com",
  TRON: import.meta.env.VITE_FALCONX_EXPLORER_TRON ?? "https://nile.tronscan.org/#",
  SOL: import.meta.env.VITE_FALCONX_EXPLORER_SOL ?? "https://solscan.io",
};

/** 浏览器友好名，按钮 / 行文显示用。 */
export const EXPLORER_NAME: Record<ChainType, string> = {
  ETH: EXPLORER_BASE.ETH.includes("sepolia") ? "Sepolia Etherscan" : "Etherscan",
  BSC: EXPLORER_BASE.BSC.includes("testnet") ? "BscScan Testnet" : "BscScan",
  TRON: EXPLORER_BASE.TRON.includes("nile") ? "TronScan Nile" : "TronScan",
  SOL: "SolScan",
};

/** 构造 tx 跳转 URL。 */
export function txExplorerUrl(chain: ChainType, txHash: string): string {
  const base = EXPLORER_BASE[chain];
  if (chain === "TRON") return `${base}/transaction/${txHash}`;
  return `${base}/tx/${txHash}`;
}

/** 由链类型推断网络代号，未知返回空字符串。 */
export function networkOf(chain: ChainType | string | null | undefined): string {
  if (!chain) return "";
  return CHAIN_TO_NETWORK[chain as ChainType] ?? "";
}

const NETWORK_TO_CHAIN: Record<string, ChainType> = {
  ERC20: "ETH",
  BEP20: "BSC",
  TRC20: "TRON",
  SPL: "SOL",
};

/** WithdrawItem.network 是「ERC20/TRC20」口径，这里反查到对应 chain 再走 txExplorerUrl。 */
export function txExplorerUrlByNetwork(
  network: string | null | undefined,
  txHash: string,
): string | null {
  if (!network) return null;
  const chain = NETWORK_TO_CHAIN[network];
  if (!chain) return null;
  return txExplorerUrl(chain, txHash);
}
