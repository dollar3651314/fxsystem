import { formatMoney, formatPnl, formatSignedPnl } from "../../lib/precision";

/** 手续费 / swap 类 bizType → 4 位金额。 */
const FEE_BIZ_TYPES = new Set([
  "ORDER_FEE_CHARGED",
  "TRADE_FEE",
  "SWAP_CHARGE",
  "SWAP_INCOME",
]);

/** 盈亏类 bizType → 有效数字 PnL（小额可见）。 */
const PNL_BIZ_TYPES = new Set([
  "REALIZED_PNL",
  "LIQUIDATION_PNL",
  "POSITION_CLOSE_PNL",
]);

export function isLedgerFeeType(bizType: string): boolean {
  return FEE_BIZ_TYPES.has(bizType);
}

export function isLedgerPnlType(bizType: string): boolean {
  return PNL_BIZ_TYPES.has(bizType);
}

/**
 * 资金流水金额格式化：按 bizType 语义分类，收敛精度映射。
 * - 手续费 / swap → formatMoney(amount, currency, 4)（4 位）
 * - 盈亏类 → formatSignedPnl(amount)（有效数字 + 自带符号，微小盈亏可见）
 * - 其余（保证金 / 冻结 / 入金 / 出金 / 调整等）→ formatMoney(amount, currency)（币种精度，默认 2）
 *
 * signed=false 时返回「无符号量级」用于调用方自行决定符号 / 颜色（如表格按余额变化方向加 +/-）：
 * - 手续费 → 4 位量级，盈亏 → formatPnl（不带 +），其余 → 2 位量级。
 */
export function formatLedgerAmount(
  amount: number | string | null | undefined,
  bizType: string,
  currency?: string | null,
  signed = true,
): string {
  if (FEE_BIZ_TYPES.has(bizType)) {
    return formatMoney(amount, currency, 4);
  }
  if (PNL_BIZ_TYPES.has(bizType)) {
    return signed ? formatSignedPnl(amount) : formatPnl(amount);
  }
  return formatMoney(amount, currency);
}
