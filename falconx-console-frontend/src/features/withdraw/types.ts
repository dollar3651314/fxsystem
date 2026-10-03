/**
 * STAGE-7-WITHDRAW Phase 4 出金审核类型，对齐管理端接口规范 §10。
 *
 * 雪花 ID 在 BBook 项目中远超 JS Number.MAX_SAFE_INTEGER (2^53-1)，
 * 后端通过 @JsonSerialize(ToStringSerializer) 序列化为 string，前端必须以 string 接收（FX-071）。
 */

export type SnowflakeId = string;

export type WithdrawStatus =
  | "COOLING"
  | "PENDING"
  | "APPROVED"
  | "APPROVED_DELAYED"
  | "PROCESSING"
  | "COMPLETED"
  | "FAILED"
  | "CANCELED"
  | "REJECTED";

export type WithdrawNetwork = "ERC20" | "TRC20";

export interface AdminWithdrawItem {
  withdrawId: SnowflakeId;
  userId: SnowflakeId;
  amount: string; // BigDecimal as string，避免精度丢失
  currency: string;
  network: WithdrawNetwork;
  targetAddress: string;
  status: WithdrawStatus;
  coolingUntil: string | null;
  delayedUntil: string | null;
  rejectReason: string | null;
  txHash: string | null;
  confirmations: number | null;
  failureReason: string | null;
  createdAt: string;
  /** Phase 4 §4 commit C：跨 schema enrich（identity + trading），enrichment 失败时 null */
  kycLevel?: number | null;
  /** Phase 4 §4 commit C：用户邮箱（跨 schema enrich）。 */
  userEmail?: string | null;
  /**
   * Phase 4 §4 commit C：用户当日 UTC 已创建且仍计入单日上限的累计出金 USDT（与 $30K 单日上限同口径）。
   * BigDecimal 序列化为 string；为 0 表示当日无累计，null 表示 enrich 失败。
   */
  dailyAccumulatedUsd?: string | null;
  /** 用户对外短号 / 姓名（跨 schema enrich，邮箱见 userEmail）。 */
  userUid?: string | null;
  userFullName?: string | null;
}

export interface AdminWithdrawListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: AdminWithdrawItem[];
}

export interface AdminWithdrawListQuery {
  status?: WithdrawStatus;
  userId?: SnowflakeId;
  network?: WithdrawNetwork;
  minAmount?: string;
  maxAmount?: string;
  page?: number;
  pageSize?: number;
}

/** 状态展示元数据：颜色、文案、图标（来自 R3 设计 §2.2）。 */
export interface WithdrawStatusMeta {
  color: string;
  label: string;
  icon: string;
}

export const WITHDRAW_STATUS_META: Record<WithdrawStatus, WithdrawStatusMeta> = {
  COOLING: { color: "default", label: "冷静期", icon: "⏳" },
  PENDING: { color: "orange", label: "待审核", icon: "⏰" },
  APPROVED: { color: "blue", label: "已批准", icon: "" },
  APPROVED_DELAYED: { color: "purple", label: "延迟期", icon: "⏱" },
  PROCESSING: { color: "blue", label: "处理中", icon: "⏳" },
  COMPLETED: { color: "green", label: "已完成", icon: "✅" },
  FAILED: { color: "red", label: "失败", icon: "❌" },
  CANCELED: { color: "default", label: "已取消", icon: "🚫" },
  REJECTED: { color: "red", label: "已拒绝", icon: "❌" },
};
