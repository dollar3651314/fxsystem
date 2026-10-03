/**
 * 显示精度统一管理（仅显示层；计算/存储/账本一律后端全精度 DECIMAL(24,8)，不在此截断）。
 *
 * 精度按数字「语义类别」分类，真源唯一：
 * - 价格 → symbol.pricePrecision（沿用 features/market/marketFormat 的 formatPrice，本模块 re-export）
 * - 数量 → symbol.qtyPrecision（formatQty）
 * - 金额（币种感知）→ 币种精度表 CURRENCY_SCALE（formatMoney / formatSignedMoney）
 * - 百分比 → 固定位数（formatPercent）
 *
 * 截断方向：一律「向零截断」（drop 多余位、保留符号），匹配产品口径「多余小数直接截断」，
 * 与 marketFormat.formatPrice 一致。注意：各值各自从全精度独立格式化，求和请用全精度算总额再格式化
 * （各行格式化值相加可能与总额格式化值差 < 最小显示单位，属预期）。
 */
export { formatPrice } from "../features/market/marketFormat";

/** 币种显示精度表：USD 系稳定币 2 位，零小数法币（JPY/KRW）0 位，默认 2。 */
const CURRENCY_SCALE: Record<string, number> = {
  USD: 2,
  USDT: 2,
  USDC: 2,
  JPY: 0,
  KRW: 0,
};

/** 取币种显示小数位；未知币种默认 2。 */
export function currencyScale(currency?: string | null): number {
  if (!currency) return 2;
  return CURRENCY_SCALE[currency.toUpperCase()] ?? 2;
}

/**
 * 向零截断到 scale 位小数（display-only）。
 * 加极小 epsilon 抵消浮点表示误差（如 1.005*100=100.4999…），且仅向零方向、不夸大数值。
 */
export function truncToScale(value: number, scale: number): number {
  if (!Number.isFinite(value)) return value;
  if (scale <= 0) return Math.trunc(value);
  const factor = Math.pow(10, scale);
  const eps = value >= 0 ? 1e-9 : -1e-9;
  return Math.trunc(value * factor + eps) / factor;
}

function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === "" || value === "--") return null;
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

function fixedString(n: number, scale: number): string {
  return truncToScale(n, scale).toLocaleString("en-US", {
    minimumFractionDigits: scale,
    maximumFractionDigits: scale,
  });
}

/**
 * 金额（币种感知）：按币种精度向零截断并定位显示；null/非法 → "--"。
 * scaleOverride 可强制指定小数位（如手续费/swap 用 4 位），缺省则按币种精度。
 */
export function formatMoney(
  value: number | string | null | undefined,
  currency?: string | null,
  scaleOverride?: number,
): string {
  const n = toNumber(value);
  if (n === null) return "--";
  const scale = typeof scaleOverride === "number" ? scaleOverride : currencyScale(currency);
  return fixedString(n, scale);
}

/**
 * 带符号金额（PnL 等）：正数加 "+" 前缀，负数自带 "-"，0 不加号。
 * scaleOverride 同 formatMoney。
 */
export function formatSignedMoney(
  value: number | string | null | undefined,
  currency?: string | null,
  scaleOverride?: number,
): string {
  const n = toNumber(value);
  if (n === null) return "--";
  const scale = typeof scaleOverride === "number" ? scaleOverride : currencyScale(currency);
  const s = fixedString(n, scale);
  return n > 0 ? `+${s}` : s;
}

/**
 * PnL（盈亏）等小额量显示：有效数字口径（四舍五入），避免按余额 2 位规则把微小盈亏截成 0。
 * 规则：|v|≥1 → 2 位小数（如 12.3456→12.35）；0<|v|<1 → 保留 sigDigits 位有效数字、跳过前导零
 * （如 -0.00216674→-0.0022、0.0000216→0.000022、-0.0015012→-0.0015）；0 → "0.00"；null→"--"。
 * 注：PnL 用四舍五入（更准确呈现微小值），区别于余额/价格的向零截断。仅用于盈亏类，不用于余额/保证金（仍 2 位）。
 */
export function formatPnl(value: number | string | null | undefined, sigDigits = 2): string {
  const n = toNumber(value);
  if (n === null) return "--";
  if (n === 0) return "0.00";
  const abs = Math.abs(n);
  let decimals: number;
  if (abs >= 1) {
    decimals = 2;
  } else {
    const firstSigPlace = Math.ceil(-Math.log10(abs) - 1e-9);
    decimals = Math.max(2, firstSigPlace + sigDigits - 1);
  }
  decimals = Math.min(decimals, 18);
  return n.toLocaleString("en-US", { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/** 带符号 PnL：正数加 "+" 前缀（用于净盈亏/总额列），负数自带 "-"，0 不加号。 */
export function formatSignedPnl(value: number | string | null | undefined, sigDigits = 2): string {
  const n = toNumber(value);
  if (n === null) return "--";
  const s = formatPnl(n, sigDigits);
  return n > 0 ? `+${s}` : s;
}

/** 数量：按 symbol qtyPrecision 向零截断；缺省精度回退 2。 */
export function formatQty(value: number | string | null | undefined, qtyPrecision?: number | null): string {
  const n = toNumber(value);
  if (n === null) return "--";
  const scale = typeof qtyPrecision === "number" && qtyPrecision >= 0 ? qtyPrecision : 2;
  return fixedString(n, scale);
}

/**
 * 百分比：入参为「已是百分数的值」（如 1.23 表示 1.23%，不是比率 0.0123）。
 * 向零截断到 digits 位 + "%"。
 */
export function formatPercent(value: number | string | null | undefined, digits = 2): string {
  const n = toNumber(value);
  if (n === null) return "--";
  return `${truncToScale(n, digits).toFixed(digits)}%`;
}
