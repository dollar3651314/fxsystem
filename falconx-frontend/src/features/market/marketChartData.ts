import type { CandlestickData, LineData, UTCTimestamp } from "lightweight-charts";
import { isKlineTimeframe, type ChartTimeframe } from "./chartTimeframes";
import type { Kline, MarketSymbol, Quote } from "./marketTypes";

type BuildCandlestickInput = {
  timeframe: ChartTimeframe;
  klineHistory: Kline[];
  // 2026-05-26 断层修复：原 liveKline: Kline | undefined（单根）会导致 wss 推送的
  // 已收盘 K 线被新活跃 K 线覆盖。改成数组 — store 累积所有推送过来的 K 线，
  // chart 按 openTime 合并 history + live，wss 优先（覆盖 history 同 openTime）。
  liveKlines: Kline[];
  /**
   * 2026-06-03 组加点平移：后端 K 线由平台基准价生成（全组共用一份），加点组的
   * 用户价（mid 线/报价/成交）与基准蜡烛之间会差一个常数 → 把蜡烛整体平移
   * markupOffset（= 当前 tick 的 mid − baseMid，见 {@link resolveMarkupOffset}），
   * 让图表呈现「本组价格世界」（MT4/MT5 同口径：图表跟实际下发的可交易流走）。
   * 常数平移不改任何形态/指标差值；非加点组恒为 0。缺省 0。
   */
  markupOffset?: number;
};

type BuildTickQuoteLineInput = {
  quoteHistory: Quote[];
  quote: Quote | undefined;
  side: "bid" | "ask";
};

const KLINE_HISTORY_WINDOW_MULTIPLIER = 220;

export function buildCandlesticks({
  timeframe,
  klineHistory,
  liveKlines,
  markupOffset = 0
}: BuildCandlestickInput): CandlestickData[] {
  const candles = new Map<number, CandlestickData>();

  if (isKlineTimeframe(timeframe)) {
    for (const kline of klineHistory) {
      const candle = klineToCandle(kline, markupOffset);
      if (candle) {
        candles.set(Number(candle.time), candle);
      }
    }

    // wss 累积 K 线优先 — 覆盖 history 中同 openTime 的旧版本（active → finalized 演进）
    for (const kline of liveKlines) {
      const candle = klineToCandle(kline, markupOffset);
      if (candle) {
        candles.set(Number(candle.time), candle);
      }
    }

    return clipToRecentKlineWindow(
      [...candles.values()].sort((left, right) => Number(left.time) - Number(right.time))
    );
  }

  return [];
}

export function buildTickLineData(
  quoteHistory: Quote[],
  quote: Quote | undefined
): LineData[] {
  return buildQuoteLineData(quoteHistory, quote, "bid");
}

export function buildTickQuoteLineData({
  quoteHistory,
  quote,
  side
}: BuildTickQuoteLineInput): LineData[] {
  return buildQuoteLineData(quoteHistory, quote, side);
}

export function buildQuoteLineData(
  quoteHistory: Quote[],
  quote: Quote | undefined,
  side: "bid" | "ask"
): LineData[] {
  // 用毫秒精度（lightweight-charts UTCTimestamp 接受浮点秒）：高频报价同一秒内可能多条，
  // 之前 Math.floor(ms/1000) + Map 去重会让数百条 tick 塌缩到几十个点。
  const points = new Map<number, LineData>();

  for (const tick of dedupeQuotes([...quoteHistory, ...(quote ? [quote] : [])]).filter(isLiveQuote)) {
    const millis = Date.parse(tick.ts);
    const tickPrice = Number(tick[side]);
    if (!Number.isFinite(millis) || !Number.isFinite(tickPrice)) {
      continue;
    }

    const tickTime = (millis / 1000) as UTCTimestamp;
    points.set(millis, {
      time: tickTime,
      value: tickPrice
    });
  }

  return [...points.values()].sort((left, right) => Number(left.time) - Number(right.time));
}

export function symbolToQuote(symbol: MarketSymbol | undefined): Quote | undefined {
  if (!symbol) {
    return undefined;
  }

  const bid = firstValue(symbol.bid, symbol.mark, symbol.mid, symbol.ask);
  if (!bid) {
    return undefined;
  }

  const ask = symbol.ask ?? bid;
  const mid = symbol.mid ?? symbol.mark ?? bid;

  return {
    symbol: symbol.symbol,
    bid,
    ask,
    mid,
    mark: symbol.mark ?? mid,
    ts: symbol.quoteTs ?? new Date().toISOString(),
    source: symbol.quoteSource ?? "REFERENCE",
    stale: symbol.priceStatus !== "LIVE",
    priceStatus: symbol.priceStatus ?? "REFERENCE",
    quoteStatus: symbol.priceStatus === "LIVE" ? "FRESH" : symbol.priceStatus
  };
}

/**
 * 组加点平移取数源：从行情流自身导出 offset = mid − baseMid（两字段同条 tick 成对下发，
 * 与报价定义上自洽——运营改加点/启停的生效瞬间自动跟上，无需另拉配置）。
 * baseMid 缺失（未加点 / 旧快照 / REST symbols 列表）或非有限值时返回 0（蜡烛即基准价，与旧行为一致）。
 * 结果按 1e-8 取整，抵消浮点减法尾差。
 */
export function resolveMarkupOffset(quote: Quote | undefined): number {
  if (!quote?.baseMid) {
    return 0;
  }
  const mid = Number(quote.mid);
  const baseMid = Number(quote.baseMid);
  if (!Number.isFinite(mid) || !Number.isFinite(baseMid)) {
    return 0;
  }
  return Math.round((mid - baseMid) * 1e8) / 1e8;
}

function klineToCandle(kline: Kline, markupOffset = 0): CandlestickData | null {
  const time = toUtcTimestamp(kline.openTime);
  const open = Number(kline.open) + markupOffset;
  const high = Number(kline.high) + markupOffset;
  const low = Number(kline.low) + markupOffset;
  const close = Number(kline.close) + markupOffset;

  if (!time || ![open, high, low, close].every(Number.isFinite)) {
    return null;
  }

  return { time, open, high, low, close };
}

function clipToRecentKlineWindow(candles: CandlestickData[]): CandlestickData[] {
  if (candles.length <= KLINE_HISTORY_WINDOW_MULTIPLIER) {
    return candles;
  }
  return candles.slice(candles.length - KLINE_HISTORY_WINDOW_MULTIPLIER);
}

function dedupeQuotes(quotes: Quote[]): Quote[] {
  const byTime = new Map<string, Quote>();
  for (const quote of quotes) {
    byTime.set(`${quote.symbol}:${quote.ts}`, quote);
  }

  return [...byTime.values()].sort(
    (left, right) => Date.parse(left.ts) - Date.parse(right.ts)
  );
}

function isLiveQuote(quote: Quote): boolean {
  return quote.stale === false && quote.priceStatus === "LIVE";
}

function firstValue(
  ...values: Array<string | null | undefined>
): string | undefined {
  return values.find((value): value is string => Boolean(value));
}

function toUtcTimestamp(value: string): UTCTimestamp | null {
  const millis = Date.parse(value);
  if (!Number.isFinite(millis)) {
    return null;
  }

  return Math.floor(millis / 1000) as UTCTimestamp;
}
