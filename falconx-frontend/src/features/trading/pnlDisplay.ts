/**
 * STAGE-14E1 Task7：持仓双币 PnL 展示助手。
 *
 * 后端硬切双币后，每个持仓的未实现盈亏有三口径：
 * - unrealizedPnlInAccount：以账户结算币（USDT）计，**主显**口径。
 * - unrealizedPnlInQuote：以持仓报价币（quoteCurrency，如 AUD）计，**副显**口径。
 * - quoteCurrency：报价币代码（来自后端 position / pnl patch 字段）。
 *
 * 账户币是平台结算 token（USDT），作为常量；报价币优先从数据来。
 * 同币种（quoteCurrency === USDT）时 inQuote === inAccount，副行冗余，调用方应省略副行。
 */

/** 平台账户结算币（settlement token）。所有持仓的账户币口径都按此计价。 */
export const ACCOUNT_CURRENCY = "USDT";

/** PnL 三态盈亏色 class（复用 fx-pnl-pos / fx-pnl-neg token）。null/非数值 → 无 class。 */
export function pnlColorClass(value: string | number | null | undefined): string {
  if (value == null) return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-pnl-pos" : n < 0 ? "fx-pnl-neg" : "";
}

/** 移动端卡片用：fx-long / fx-short 口径（与 side 着色一致）。 */
export function pnlSideClass(value: string | null | undefined): string {
  if (value == null) return "";
  const n = Number(value);
  if (!Number.isFinite(n)) return "";
  return n > 0 ? "fx-long" : n < 0 ? "fx-short" : "";
}

/**
 * 是否需要展示报价币副行。
 * 同币种（报价币就是账户币 USDT）或缺报价币信息时无需副行（避免与主行冗余）。
 */
export function shouldShowQuoteRow(
  quoteCurrency: string | null | undefined,
  unrealizedPnlInQuote: string | null | undefined,
): boolean {
  if (quoteCurrency == null || quoteCurrency === "") return false;
  if (quoteCurrency === ACCOUNT_CURRENCY) return false;
  return unrealizedPnlInQuote != null;
}
