import { apiBaseUrl } from "../../lib/config";
import { type ApiResponse, FalconApiError, requestJson, withAccessTokenRetry } from "../../lib/api";

export interface DepositAddressItem {
  network: string; // "ERC20" / "TRC20"
  chain: string; // "ETH" / "TRON"
  token: string; // "USDT"
  address: string;
  addressIndex: number;
  derivationPath: string;
}

export interface EnsureDepositAddressesResponse {
  addresses: DepositAddressItem[];
}

/**
 * 调 wallet-service POST /api/v1/wallet/deposit-addresses/ensure：
 * - 已有派生地址 → 直接返回
 * - 没有 → 派生 ERC20 + TRC20 默认 USDT 地址（幂等）
 */
export function ensureDepositAddresses(token: string): Promise<EnsureDepositAddressesResponse> {
  return requestJson<EnsureDepositAddressesResponse>("/api/v1/wallet/deposit-addresses/ensure", {
    method: "POST",
    token,
    body: "{}",
  });
}

/**
 * 与后端 com.falconx.trading.entity.TradingLedgerBizType 一一对应。
 * | 类型保底是 string 兜底，避免后端加新枚举前端立刻挂。
 */
export type LedgerBizType =
  // 入金
  | "DEPOSIT_CREDIT"
  | "DEPOSIT_REVERSAL"
  // 下单 / 挂单 / 持仓内部状态
  | "ORDER_MARGIN_RESERVED"
  | "ORDER_FEE_CHARGED"
  | "ORDER_MARGIN_CONFIRMED"
  | "ISOLATED_MARGIN_SUPPLEMENT"
  | "PENDING_ORDER_RELEASED"
  // 盈亏 / Swap
  | "SWAP_CHARGE"
  | "SWAP_INCOME"
  | "REALIZED_PNL"
  | "LIQUIDATION_PNL"
  // 管理员
  | "ADMIN_BALANCE_ADJUST"
  // 出金（5 个状态）
  | "WITHDRAW_FREEZE"
  | "WITHDRAW_REFUND_CANCEL"
  | "WITHDRAW_REFUND_REJECT"
  | "WITHDRAW_REFUND_EMERGENCY"
  | "WITHDRAW_SETTLE"
  | "WITHDRAW_REFUND_CHAIN_FAILED"
  // 历史 / 已废弃保留兼容
  | "TRADE_FEE"
  | "POSITION_OPEN"
  | "POSITION_CLOSE_PNL"
  | "LIQUIDATION"
  | "DEPOSIT"
  | "ADJUST"
  | "SWAP"
  | string;

export interface LedgerEntry {
  ledgerId: string;
  bizType: LedgerBizType;
  amount: string;
  idempotencyKey: string | null;
  referenceNo: string | null;
  balanceBefore: string;
  balanceAfter: string;
  frozenBefore: string;
  frozenAfter: string;
  marginUsedBefore: string;
  marginUsedAfter: string;
  createdAt: string;
}

export interface LedgerListResponse {
  page: number;
  pageSize: number;
  total: number;
  items: LedgerEntry[];
}

const ID_FIELDS_RE = /("(?:ledgerId|withdrawId|orderId|positionId)":)\s*(\d{15,})/g;

export interface LedgerFilter {
  bizType?: string;
  /** ISO datetime string e.g. "2026-05-20T00:00:00Z" */
  from?: string;
  /** ISO datetime string */
  to?: string;
}

export async function listLedger(
  token: string,
  page = 1,
  pageSize = 20,
  filter?: LedgerFilter,
): Promise<LedgerListResponse> {
  const params = new URLSearchParams();
  params.set("page", String(page));
  params.set("pageSize", String(pageSize));
  if (filter?.bizType) params.set("bizType", filter.bizType);
  if (filter?.from) params.set("from", filter.from);
  if (filter?.to) params.set("to", filter.to);
  return withAccessTokenRetry(token, async (tk) => {
    const resp = await fetch(`${apiBaseUrl}/api/v1/trading/ledger?${params.toString()}`, {
      headers: { Authorization: `Bearer ${tk}` },
    });
    const raw = await resp.text();
    const wrapped = raw.replace(ID_FIELDS_RE, '$1"$2"');
    const payload = JSON.parse(wrapped) as ApiResponse<LedgerListResponse>;
    const unauthorized = resp.status === 401 || payload.code === "10001";
    if (!unauthorized && (payload.code !== "0" || payload.data == null)) {
      throw new FalconApiError(payload.code, payload.message, payload.traceId, resp.status, payload.data);
    }
    return { unauthorized, value: payload.data as LedgerListResponse };
  });
}

/**
 * 友好中文文案，与后端 com.falconx.trading.entity.TradingLedgerBizType 枚举一一对应。
 *
 * 显示原则（与 LedgerSignKind 配合）：
 *   - DEPOSIT_CREDIT / 退款类 / 收益类     → 金额前 +（正向，绿）
 *   - ORDER_FEE_CHARGED / SWAP_CHARGE / WITHDRAW_SETTLE 等  → 金额前 -（负向，红）
 *   - ORDER_MARGIN_RESERVED / CONFIRMED / 内部移动 → 不加符号（balance 不变，灰）
 *   - REALIZED_PNL / LIQUIDATION_PNL / ADMIN_BALANCE_ADJUST → 按 balanceAfter-balanceBefore 实际方向定
 */
export const LEDGER_BIZ_TYPE_LABEL: Record<string, string> = {
  // 入金
  DEPOSIT_CREDIT: "入金到账",
  DEPOSIT_REVERSAL: "入金回滚",
  // 下单 / 持仓
  ORDER_MARGIN_RESERVED: "下单冻结保证金",
  ORDER_FEE_CHARGED: "下单手续费",
  ORDER_MARGIN_CONFIRMED: "下单保证金确认",
  ISOLATED_MARGIN_SUPPLEMENT: "逐仓追加保证金",
  PENDING_ORDER_RELEASED: "挂单冻结释放",
  // Swap / PnL
  SWAP_CHARGE: "Swap 持仓费用",
  SWAP_INCOME: "Swap 收益",
  REALIZED_PNL: "平仓损益",
  LIQUIDATION_PNL: "强平损益",
  // 管理员
  ADMIN_BALANCE_ADJUST: "管理员调整余额",
  // 出金（5 个分流）
  WITHDRAW_FREEZE: "出金冻结",
  WITHDRAW_REFUND_CANCEL: "出金撤销退款",
  WITHDRAW_REFUND_REJECT: "出金拒绝退款",
  WITHDRAW_REFUND_EMERGENCY: "紧急取消退款",
  WITHDRAW_SETTLE: "出金链上结算扣除",
  WITHDRAW_REFUND_CHAIN_FAILED: "出金链上失败退款",
  // 历史 / 兼容
  TRADE_FEE: "交易手续费",
  POSITION_OPEN: "开仓占用保证金",
  POSITION_CLOSE_PNL: "平仓损益",
  LIQUIDATION: "强平结算",
  DEPOSIT: "入金到账",
  ADJUST: "管理员调整",
  SWAP: "Swap 结算",
};
