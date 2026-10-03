import { requestJson } from "../../lib/api";
import { normalizePriceStatus } from "./marketFormat";
import type { Kline, KlineInterval, MarketSymbol, Quote } from "./marketTypes";

type MarketSymbolsResponse = {
  symbols: MarketSymbol[];
};

type MarketKlinesResponse = {
  klines: Kline[];
};

type MarketQuoteHistoryResponse = {
  quotes: Quote[];
};

export function getSymbols(token: string): Promise<MarketSymbol[]> {
  return requestJson<MarketSymbolsResponse>("/api/v1/market/symbols", { token }).then(
    (response) => response.symbols
  );
}

type FeaturedSymbolsResponse = {
  symbols: string[];
};

/**
 * 跑马灯热门产品（管理端配置，启用项有序）。空数组 = 管理端未配置，
 * 调用方回退前端默认偏好（resolveTickerSymbols）。
 */
export function getFeaturedSymbols(token: string): Promise<string[]> {
  return requestJson<FeaturedSymbolsResponse>("/api/v1/market/symbols/featured", { token }).then(
    (response) => response.symbols ?? []
  );
}

export function getQuote(token: string, symbol: string): Promise<Quote> {
  return requestJson<Quote>(
    `/api/v1/market/quotes/${encodeURIComponent(symbol)}`,
    { token }
  );
}

export function getKlines(
  token: string,
  symbol: string,
  interval: KlineInterval = "1m",
  limit = 200
): Promise<Kline[]> {
  const params = new URLSearchParams({
    interval,
    limit: String(limit)
  });

  return requestJson<MarketKlinesResponse>(
    `/api/v1/market/klines/${encodeURIComponent(symbol)}?${params.toString()}`,
    { token }
  ).then((response) => response.klines);
}

export function getQuoteHistory(
  token: string,
  symbol: string,
  limit = 600
): Promise<Quote[]> {
  const params = new URLSearchParams({
    limit: String(limit)
  });

  return requestJson<MarketQuoteHistoryResponse>(
    `/api/v1/market/quotes/${encodeURIComponent(symbol)}/history?${params.toString()}`,
    { token }
  ).then((response) => response.quotes.map(normalizeHistoryQuote));
}

// 后端 history 接口只返回 quoteStatus（FRESH/STALE...），不带 priceStatus；
// 而 isLiveQuote 等下游过滤逻辑判定的是 priceStatus === "LIVE"，
// 不归一化时整批 history 都会被过滤为空，Tick 图首次进入看不到历史。
function normalizeHistoryQuote(quote: Quote): Quote {
  return {
    ...quote,
    priceStatus: quote.priceStatus ?? normalizePriceStatus(quote.quoteStatus)
  };
}
