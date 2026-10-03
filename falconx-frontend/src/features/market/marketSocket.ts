import type {
  Kline,
  KlineMessage,
  MarketChannel,
  MarketDataState,
  MarketServerMessage,
  PriceTickMessage,
  Quote,
  SubscribeMessage,
  UnsubscribeMessage
} from "./marketTypes";
import { normalizePriceStatus } from "./marketFormat";

const DEFAULT_CHANNELS: MarketChannel[] = ["price.tick", "kline.1m"];
const MAX_QUOTE_HISTORY = 600;
// 2026-05-26 K 线断层修复：每 (symbol, interval) 保留最近 500 根 wss 推送
// （1m 覆盖 ~8h；5m 覆盖 ~41h；1h 覆盖 ~20 天），按 openTime upsert。
const MAX_LIVE_KLINES_PER_INTERVAL = 500;
const SUPPORTED_KLINE_TYPES = new Set([
  "kline.1m",
  "kline.5m",
  "kline.15m",
  "kline.1h",
  "kline.4h",
  "kline.1d"
]);

export function buildSubscribeMessage(
  symbols: string | string[],
  requestId: string,
  channels: MarketChannel[] = DEFAULT_CHANNELS
): SubscribeMessage {
  return {
    type: "subscribe",
    requestId,
    channels,
    symbols: normalizeSymbols(symbols)
  };
}

export function buildUnsubscribeMessage(
  symbols: string | string[],
  requestId: string,
  channels: MarketChannel[] = DEFAULT_CHANNELS
): UnsubscribeMessage {
  return {
    type: "unsubscribe",
    requestId,
    channels,
    symbols: normalizeSymbols(symbols)
  };
}

export function normalizeSymbols(symbols: string | string[]): string[] {
  const candidates = Array.isArray(symbols) ? symbols : [symbols];
  return [...new Set(candidates.map((symbol) => symbol.trim()).filter(Boolean))];
}

export function reduceMarketMessage(
  state: MarketDataState,
  message: MarketServerMessage
): MarketDataState {
  return reduceMarketMessages(state, [message]);
}

export function reduceMarketMessages(
  state: MarketDataState,
  messages: MarketServerMessage[]
): MarketDataState {
  let quotes = state.quotes;
  let quoteHistory = state.quoteHistory;
  let klines = state.klines;
  let changed = false;

  const ensureQuotes = () => {
    if (quotes === state.quotes) {
      quotes = { ...quotes };
    }
    changed = true;
    return quotes;
  };

  const ensureQuoteHistory = () => {
    if (quoteHistory === state.quoteHistory) {
      quoteHistory = { ...quoteHistory };
    }
    changed = true;
    return quoteHistory;
  };

  const ensureKlines = () => {
    if (klines === state.klines) {
      klines = { ...klines };
    }
    changed = true;
    return klines;
  };

  for (const message of messages) {
    if (isPriceTickMessage(message)) {
      const quote = messageToQuote(message);
      ensureQuotes()[message.symbol] = quote;

      if (isDrawableQuote(quote)) {
        const symbolHistory = quoteHistory[message.symbol] ?? [];
        ensureQuoteHistory()[message.symbol] = [...symbolHistory, quote].slice(
          -MAX_QUOTE_HISTORY
        );
      }
      continue;
    }

    if (isStalePriceMessage(message)) {
      const previousQuote = quotes[message.symbol];
      if (!previousQuote) {
        continue;
      }

      ensureQuotes()[message.symbol] = {
        ...previousQuote,
        ts: message.ts,
        receivedAt: message.receivedAt,
        stale: true,
        priceStatus: normalizePriceStatus(message.quoteStatus) ?? "REFERENCE",
        quoteStatus: message.quoteStatus,
        qualityReason: message.qualityReason
      };
      continue;
    }

    if (isKlineMessage(message)) {
      // 2026-05-26 断层修复：按 openTime upsert 累积到数组（同 openTime 的 finalized
      // 版本覆盖 active 版本），保留最近 MAX_LIVE_KLINES_PER_INTERVAL 根。
      const newKline: Kline = {
        symbol: message.symbol,
        interval: message.interval,
        open: message.open,
        high: message.high,
        low: message.low,
        close: message.close,
        volume: message.volume,
        openTime: message.openTime,
        closeTime: message.closeTime,
        isFinal: message.isFinal
      };
      const symbolKlines = { ...(klines[message.symbol] ?? {}) };
      const existing = symbolKlines[message.interval] ?? [];
      symbolKlines[message.interval] = upsertKlineByOpenTime(existing, newKline);
      ensureKlines()[message.symbol] = symbolKlines;
    }
  }

  return changed ? { quotes, quoteHistory, klines } : state;
}

function messageToQuote(message: PriceTickMessage): Quote {
  return {
    symbol: message.symbol,
    bid: message.bid,
    ask: message.ask,
    mid: message.mid,
    mark: message.mark,
    // STAGE-12 基准价字段必须透传进 store：K 线组加点平移（resolveMarkupOffset =
    // mid − baseMid）依赖它们——2026-06-03 修复此前映射丢弃 base* 致 offset 恒 0、蜡烛不平移。
    baseBid: message.baseBid,
    baseAsk: message.baseAsk,
    baseMid: message.baseMid,
    hasMarkup: message.hasMarkup,
    ts: message.ts,
    receivedAt: message.receivedAt,
    source: message.source,
    stale: message.stale,
    priceStatus: normalizePriceStatus(message.quoteStatus ?? message.priceStatus),
    quoteStatus: message.quoteStatus,
    qualityReason: message.qualityReason
  };
}

/**
 * 2026-05-26 K 线断层修复：按 {@code openTime} upsert wss 推送的 K 线。
 *
 * <p>同 openTime 的后到推送覆盖前到（active → finalized 演进），按 openTime 升序排序，
 * 保留最近 {@link MAX_LIVE_KLINES_PER_INTERVAL} 根。
 */
export function upsertKlineByOpenTime(existing: Kline[], incoming: Kline): Kline[] {
  const byTime = new Map<string, Kline>();
  for (const kline of existing) {
    byTime.set(kline.openTime, kline);
  }
  byTime.set(incoming.openTime, incoming);
  const sorted = [...byTime.values()].sort((left, right) =>
    Date.parse(left.openTime) - Date.parse(right.openTime)
  );
  if (sorted.length <= MAX_LIVE_KLINES_PER_INTERVAL) {
    return sorted;
  }
  return sorted.slice(sorted.length - MAX_LIVE_KLINES_PER_INTERVAL);
}

function isPriceTickMessage(
  message: MarketServerMessage
): message is PriceTickMessage {
  return (
    message.type === "price.tick" &&
    typeof message.symbol === "string" &&
    typeof message.bid === "string" &&
    typeof message.ask === "string" &&
    typeof message.mid === "string" &&
    typeof message.mark === "string" &&
    typeof message.ts === "string" &&
    (message.receivedAt === undefined || typeof message.receivedAt === "string") &&
    typeof message.source === "string" &&
    typeof message.stale === "boolean"
  );
}

function isStalePriceMessage(
  message: MarketServerMessage
): message is {
  type: "price.tick";
  symbol: string;
  stale: true;
  quoteStatus?: string;
  qualityReason?: string | null;
  ts: string;
  receivedAt?: string;
} {
  return (
    message.type === "price.tick" &&
    typeof message.symbol === "string" &&
    message.stale === true &&
    typeof message.ts === "string" &&
    (message.receivedAt === undefined || typeof message.receivedAt === "string") &&
    (message.quoteStatus === undefined || typeof message.quoteStatus === "string") &&
    (message.qualityReason === undefined ||
      message.qualityReason === null ||
      typeof message.qualityReason === "string")
  );
}

function isKlineMessage(message: MarketServerMessage): message is KlineMessage {
  const candidate = message as Record<string, unknown>;

  return (
    typeof candidate.type === "string" &&
    SUPPORTED_KLINE_TYPES.has(candidate.type) &&
    typeof candidate.symbol === "string" &&
    typeof candidate.interval === "string" &&
    typeof candidate.open === "string" &&
    typeof candidate.high === "string" &&
    typeof candidate.low === "string" &&
    typeof candidate.close === "string" &&
    typeof candidate.volume === "string" &&
    typeof candidate.openTime === "string" &&
    typeof candidate.closeTime === "string" &&
    typeof candidate.isFinal === "boolean"
  );
}

function isDrawableQuote(quote: Quote): boolean {
  return quote.stale === false && quote.priceStatus === "LIVE";
}
