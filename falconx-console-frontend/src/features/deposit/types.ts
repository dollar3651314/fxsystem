export type SnowflakeId = string;

export type DepositStatus = "DETECTED" | "CONFIRMING" | "CONFIRMED" | "REVERSED" | "IGNORED";
export type ChainType = "ETH" | "BSC" | "TRON" | "SOL";

/**
 * trading-core 端入账状态（V23 起从 t_deposit 派生）。
 * - CREDITED：trading-core 已成功入账（t_deposit.status=1），用户余额已 +amount
 * - REJECTED：token 不在白名单等被拒收（t_deposit.status=3 + rejection_reason）
 * - REVERSED：曾入账后又被回滚（t_deposit.status=2）
 * - PENDING：wallet 端已确认但 trading consumer 尚未消费 / 写入（极短窗口或孤儿）
 * - UNKNOWN：状态码异常（理论不应出现）
 */
export type DepositCreditStatus =
  | "CREDITED"
  | "REJECTED"
  | "REVERSED"
  | "PENDING"
  | "UNKNOWN";

export interface DepositItem {
  id: SnowflakeId;
  userId: SnowflakeId | null;
  chain: ChainType;
  token: string;
  tokenContractAddress: string | null;
  txHash: string;
  logIndex: number;
  fromAddress: string;
  toAddress: string;
  amount: string;
  blockNumber: number | null;
  confirmations: number;
  requiredConfirms: number;
  status: DepositStatus;
  detectedAt: string;
  confirmedAt: string | null;
  updatedAt: string;
  /** trading-core 入账状态，console-service 跨 schema 拼出来。 */
  creditStatus?: DepositCreditStatus | null;
  /** 拒收原因，仅 creditStatus=REJECTED 时有值（如 token_not_whitelisted）。 */
  rejectionReason?: string | null;
  /** 后端跨 schema enrich：用户对外短号 / 邮箱 / 姓名（可能为 null）。 */
  userUid?: string | null;
  userEmail?: string | null;
  userFullName?: string | null;
}

export interface DepositListResponse {
  items: DepositItem[];
  total: number;
  page: number;
  size: number;
}

export interface DepositListQuery {
  userId?: number;
  chain?: ChainType;
  token?: string;
  status?: DepositStatus[];
  fromDetectedAt?: string;
  toDetectedAt?: string;
  onlyOrphan?: boolean;
  page?: number;
  size?: number;
}
