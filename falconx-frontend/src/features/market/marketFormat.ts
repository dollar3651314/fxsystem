import type { MarketSymbol, PriceStatus, Quote } from "./marketTypes";

export function resolvePriceStatus(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): PriceStatus {
  return quote?.priceStatus ?? symbol?.priceStatus ?? "MISSING";
}

export function isTradable(symbol: MarketSymbol | undefined, quote: Quote | undefined) {
  if (quote?.priceStatus === "LIVE") {
    return true;
  }

  return symbol?.tradable === true && resolvePriceStatus(symbol, quote) === "LIVE";
}

/**
 * 行情大字"当前价"。业界 (OKX/Binance/MT5) 标准用 mid (公允中间价)，与下方
 * Bid/Ask/Mid 三个 Metric + K 线 OHLC 口径一致。避免与下单面板 mid 价 (OrderTicket)
 * 出现 bid vs mid 视觉割裂（用户疑惑"为什么两边价格不一样"）。
 * Fallback 顺序：mid → mark → bid → ask （旧后端缺 mid 时降级）。
 */
export function resolveDisplayPrice(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): string {
  return (
    quote?.mid ??
    symbol?.mid ??
    quote?.mark ??
    symbol?.mark ??
    quote?.bid ??
    symbol?.bid ??
    quote?.ask ??
    symbol?.ask ??
    "--"
  );
}

export function resolveBidPrice(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): string {
  return quote?.bid ?? symbol?.bid ?? quote?.mark ?? quote?.mid ?? symbol?.mark ?? symbol?.mid ?? "--";
}

export function resolveAskPrice(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): string {
  return quote?.ask ?? symbol?.ask ?? quote?.mark ?? quote?.mid ?? symbol?.mark ?? symbol?.mid ?? "--";
}

export function resolveQuoteTimestamp(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): string | undefined {
  return quote?.ts ?? symbol?.quoteTs ?? undefined;
}

export function resolveQuoteDisplayTimestamp(
  symbol: MarketSymbol | undefined,
  quote: Quote | undefined
): string | undefined {
  return quote?.receivedAt ?? resolveQuoteTimestamp(symbol, quote);
}

export function formatPrice(value: string | number | null | undefined, precision?: number | null): string {
  if (value === null || value === undefined || value === "--") {
    return "--";
  }

  const numericValue = Number(value);
  if (!Number.isFinite(numericValue)) {
    return String(value);
  }

  // 如果传了 symbol pricePrecision，按 precision 截断（不四舍五入）：
  //   formatPrice("1.234567", 2) → "1.23"；formatPrice("1.99999", 4) → "1.9999"
  // 之前 maximumFractionDigits 走的是 round，会把 1.9999 显示成 1.99 或 2.00
  if (typeof precision === "number" && precision >= 0 && precision <= 12) {
    const factor = Math.pow(10, precision);
    const truncated = Math.trunc(numericValue * factor) / factor;
    return truncated.toLocaleString("en-US", {
      minimumFractionDigits: precision,
      maximumFractionDigits: precision
    });
  }

  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: numericValue >= 100 ? 2 : 4,
    maximumFractionDigits: numericValue >= 100 ? 2 : 6
  }).format(numericValue);
}

export function formatTimestamp(value: string | undefined): string {
  if (!value) {
    return "--";
  }

  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(timestamp);
}

export function normalizePriceStatus(value: unknown): PriceStatus | undefined {
  if (typeof value !== "string") {
    return undefined;
  }

  switch (value.toUpperCase()) {
    case "LIVE":
    case "FRESH":
      return "LIVE";
    case "REFERENCE":
    case "STALE":
    case "NO_QUOTE":
    case "MARKET_CLOSED":
    case "ABNORMAL":
      return "REFERENCE";
    case "MISSING":
      return "MISSING";
    default:
      return undefined;
  }
}

export function formatPriceStatusLabel(tradable: boolean, status: string): string {
  if (tradable && status === "LIVE") {
    return "实时";
  }

  switch (status) {
    case "LIVE":
      return "实时";
    case "REFERENCE":
      return "参考价";
    case "MISSING":
      return "缺失";
    default:
      return status;
  }
}

export function formatMarketGroup(symbol: MarketSymbol): string {
  if (symbol.marketCode) {
    return symbol.marketCode;
  }

  const labels: Record<string, string> = {
    "1": "CRYPTO",
    "2": "FX",
    "3": "METAL",
    "4": "INDEX",
    "5": "ENERGY",
    "6": "STOCK"
  };

  return labels[String(symbol.category ?? "")] ?? "CFD";
}
