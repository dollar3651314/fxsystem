/**
 * STAGE-11-OBS-RECON §14 管理端入金对账类型，对齐管理端接口规范 §14 + console-pages-V1 §17。
 *
 * 雪花 ID（walletTxId / tradingDepositId / userId）后端通过 String 序列化，前端必须以 string 接收。
 */

export type SnowflakeId = string;

export type DiscrepancyType =
  | "WALLET_ONLY"
  | "TRADING_ONLY"
  | "AMOUNT_MISMATCH"
  | "STATUS_DIVERGED";

export type ResolutionType =
  | "MANUAL_CREDIT"
  | "IGNORE_NON_BUSINESS"
  | "WALLET_FALSE_POSITIVE";

export interface AdminReconciliationItem {
  walletTxId: SnowflakeId | null;
  tradingDepositId: SnowflakeId | null;
  chain: string;
  token: string;
  txHash: string;
  walletAmount: string | null;
  tradingAmount: string | null;
  walletStatus: string | null;
  tradingStatus: string | null;
  discrepancyType: DiscrepancyType;
  walletDetectedAt: string | null;
  walletConfirmedAt: string | null;
  userId: SnowflakeId | null;
  toAddress: string | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface AdminReconciliationListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminReconciliationItem[];
}

export interface AdminReconciliationListQuery {
  chain?: string;
  token?: string;
  discrepancyType?: DiscrepancyType;
  fromDetectedAt?: string;
  toDetectedAt?: string;
  page?: number;
  size?: number;
}

export interface AdminReconciliationMarkResolvedRequest {
  reason: string;
  resolutionType: ResolutionType;
}

export interface AdminReconciliationMarkResolvedResponse {
  walletTxId: SnowflakeId;
  resolvedAt: string;
  resolvedByAdminId: SnowflakeId;
}

export const DISCREPANCY_META: Record<DiscrepancyType, { color: string; label: string }> = {
  WALLET_ONLY: { color: "warning", label: "WALLET_ONLY" },
  TRADING_ONLY: { color: "error", label: "TRADING_ONLY" },
  AMOUNT_MISMATCH: { color: "warning", label: "AMOUNT_MISMATCH" },
  STATUS_DIVERGED: { color: "error", label: "STATUS_DIVERGED" },
};

export const RESOLUTION_TYPE_OPTIONS: Array<{ value: ResolutionType; label: string }> = [
  { value: "MANUAL_CREDIT", label: "MANUAL_CREDIT — 已手工补单" },
  { value: "IGNORE_NON_BUSINESS", label: "IGNORE_NON_BUSINESS — 非业务入金忽略" },
  { value: "WALLET_FALSE_POSITIVE", label: "WALLET_FALSE_POSITIVE — wallet 误判已 reverse" },
];
