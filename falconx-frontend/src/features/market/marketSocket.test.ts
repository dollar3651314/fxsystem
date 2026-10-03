import { describe, expect, it } from "vitest";
import {
  buildSubscribeMessage,
  reduceMarketMessage,
  reduceMarketMessages
} from "./marketSocket";
import { buildCandlesticks } from "./marketChartData";

describe("marketSocket", () => {
  it("builds a backend-compatible subscribe message", () => {
    expect(buildSubscribeMessage(["BTCUSD.p", "ETHUSD.p"], "req-1", ["price.tick"])).toEqual({
      type: "subscribe",
      requestId: "req-1",
      channels: ["price.tick"],
      symbols: ["BTCUSD.p", "ETHUSD.p"]
    });
  });

  it("stores price.tick payload by symbol", () => {
    const next = reduceMarketMessage(
      { quotes: {}, quoteHistory: {}, klines: {} },
      {
        type: "price.tick",
        symbol: "BTCUSD.p",
        bid: "68000.00",
        ask: "68001.00",
        mid: "68000.50",
        mark: "68000.50",
        ts: "2026-04-30T12:00:00Z",
        receivedAt: "2026-04-30T12:00:01Z",
        source: "TM_QUOTE",
        stale: false,
        quoteStatus: "FRESH"
      }
    );

    expect(next.quotes["BTCUSD.p"]?.bid).toBe("68000.00");
    expect(next.quotes["BTCUSD.p"]?.receivedAt).toBe("2026-04-30T12:00:01Z");
    expect(next.quotes["BTCUSD.p"]?.priceStatus).toBe("LIVE");
    expect(next.quoteHistory["BTCUSD.p"]).toHaveLength(1);
  });

  it("回归：price.tick 的 baseBid/baseAsk/baseMid/hasMarkup 必须透传进 store（K 线加点平移依赖）", () => {
    // 2026-06-03 修复：messageToQuote 丢弃 base* 字段 → resolveMarkupOffset 恒 0 → 蜡烛不平移。
    const next = reduceMarketMessage(
      { quotes: {}, quoteHistory: {}, klines: {} },
      {
        type: "price.tick",
        symbol: "XAUUSD",
        bid: "4461.46",
        ask: "4462.58",
        mid: "4462.02",
        mark: "4462.02",
        baseBid: "4460.46",
        baseAsk: "4460.58",
        baseMid: "4460.52",
        hasMarkup: true,
        ts: "2026-06-03T07:36:00Z",
        source: "TM_QUOTE",
        stale: false,
        quoteStatus: "FRESH"
      }
    );

    expect(next.quotes.XAUUSD).toMatchObject({
      baseBid: "4460.46",
      baseAsk: "4460.58",
      baseMid: "4460.52",
      hasMarkup: true
    });
  });

  it("downgrades an existing live quote when backend sends a stale notification frame", () => {
    const live = reduceMarketMessage(
      { quotes: {}, quoteHistory: {}, klines: {} },
      {
        type: "price.tick",
        symbol: "EURUSD",
        bid: "1.16820",
        ask: "1.16830",
        mid: "1.16825",
        mark: "1.16825",
        ts: "2026-04-30T12:00:00Z",
        source: "TM_QUOTE",
        stale: false,
        quoteStatus: "FRESH"
      }
    );

    const stale = reduceMarketMessage(live, {
      type: "price.tick",
      symbol: "EURUSD",
      stale: true,
      quoteStatus: "STALE",
      qualityReason: "QUOTE_TIME_DRIFT_EXCEEDED",
      ts: "2026-04-30T12:00:05Z",
      receivedAt: "2026-04-30T12:00:06Z"
    });

    expect(stale.quotes.EURUSD).toMatchObject({
      bid: "1.16820",
      mark: "1.16825",
      stale: true,
      priceStatus: "REFERENCE",
      quoteStatus: "STALE",
      ts: "2026-04-30T12:00:05Z",
      receivedAt: "2026-04-30T12:00:06Z"
    });
    expect(stale.quoteHistory.EURUSD).toHaveLength(1);
  });

  it("does not append non-live quote snapshots to drawable quote history", () => {
    const next = reduceMarketMessage(
      { quotes: {}, quoteHistory: {}, klines: {} },
      {
        type: "price.tick",
        symbol: "GBPUSD",
        bid: "1.34850",
        ask: "1.34860",
        mid: "1.34855",
        mark: "1.34855",
        ts: "2026-04-30T12:00:00Z",
        source: "TM_QUOTE",
        stale: true,
        quoteStatus: "STALE"
      }
    );

    expect(next.quotes.GBPUSD?.priceStatus).toBe("REFERENCE");
    expect(next.quoteHistory.GBPUSD).toBeUndefined();
  });

  it("reduces a batch of websocket frames into one market state", () => {
    const next = reduceMarketMessages(
      { quotes: {}, quoteHistory: {}, klines: {} },
      [
        {
          type: "price.tick",
          symbol: "EURUSD",
          bid: "1.16820",
          ask: "1.16830",
          mid: "1.16825",
          mark: "1.16825",
          ts: "2026-04-30T12:00:00Z",
          source: "TM_QUOTE",
          stale: false,
          quoteStatus: "FRESH"
        },
        {
          type: "kline.1m",
          symbol: "EURUSD",
          interval: "1m",
          open: "1.16800",
          high: "1.16830",
          low: "1.16790",
          close: "1.16825",
          volume: "1",
          openTime: "2026-04-30T12:00:00Z",
          closeTime: "2026-04-30T12:00:59Z",
          isFinal: false
        }
      ]
    );

    expect(next.quotes.EURUSD?.priceStatus).toBe("LIVE");
    // 2026-05-26 断层修复后 klines[symbol][interval] 是 Kline[]
    expect(next.klines.EURUSD?.["1m"]?.[0]?.close).toBe("1.16825");
  });

  it("upserts wss klines into store by openTime (断层修复 — 不再覆盖)", () => {
    // 推 3 根不同 openTime 的 K 线 — 全部应保留
    const next = reduceMarketMessages(
      { quotes: {}, quoteHistory: {}, klines: {} },
      [
        klineMsg("XAUUSD", "1m", "2026-05-26T18:11:00Z", "4521.00", false),
        klineMsg("XAUUSD", "1m", "2026-05-26T18:12:00Z", "4522.00", false),
        klineMsg("XAUUSD", "1m", "2026-05-26T18:13:00Z", "4523.00", false)
      ]
    );

    expect(next.klines.XAUUSD?.["1m"]).toHaveLength(3);
    expect(next.klines.XAUUSD?.["1m"]?.[0]?.openTime).toBe("2026-05-26T18:11:00Z");
    expect(next.klines.XAUUSD?.["1m"]?.[2]?.openTime).toBe("2026-05-26T18:13:00Z");
  });

  it("回归：用户截图 18:11 → 18:38 断层场景 28 根 1m K 线完整保留", () => {
    // 2026-05-26 回归测试：复现用户截图断层场景。
    // 修复前 bug：每根 K 线先以 active 推 N 次，再 finalized 推一次；store 单根覆盖
    // 导致 18:11~18:37 全部被 18:38 覆盖，chart 断层 28 分钟。
    // 修复后：每根按 openTime upsert 累积，全部保留。

    // 模拟 wss 推送序列：从 18:11:00 开始，每分钟 1 根 K 线，连续 28 分钟
    // 每根 K 线先推 5 次 active（同 openTime，OHLC 持续更新），再推 1 次 finalized
    const messages: ReturnType<typeof klineMsg>[] = [];
    for (let minute = 11; minute <= 38; minute++) {
      const openTime = `2026-05-26T18:${String(minute).padStart(2, "0")}:00Z`;
      const price = (4520 + minute).toFixed(2);
      // 5 次 active 推送（OHLC 实时更新但 openTime 不变）
      for (let i = 0; i < 5; i++) {
        messages.push(klineMsg("XAUUSD", "1m", openTime, price, false));
      }
      // 1 次 finalized 推送（K 线收盘）— 只在不是最后一根（still active）的情况下推
      if (minute < 38) {
        messages.push(klineMsg("XAUUSD", "1m", openTime, price, true));
      }
    }

    const state = reduceMarketMessages(
      { quotes: {}, quoteHistory: {}, klines: {} },
      messages
    );

    // 1. store 应累积 28 根 K 线（每个 openTime 一根，FIFO upsert）
    const storeKlines = state.klines.XAUUSD?.["1m"] ?? [];
    expect(storeKlines).toHaveLength(28);

    // 2. 时间顺序正确（18:11 → 18:38 升序）
    expect(storeKlines[0]?.openTime).toBe("2026-05-26T18:11:00Z");
    expect(storeKlines[27]?.openTime).toBe("2026-05-26T18:38:00Z");

    // 3. finalized 演进生效（前 27 根 isFinal=true，最后一根仍 active）
    expect(storeKlines[26]?.isFinal).toBe(true);
    expect(storeKlines[27]?.isFinal).toBe(false);

    // 4. 喂给 buildCandlesticks（模拟初始 history 为空 — 仅靠 wss 累积也能完整渲染）
    const candles = buildCandlesticks({
      timeframe: "1m",
      klineHistory: [],   // 初始 REST 一根都没（最坏场景）
      liveKlines: storeKlines
    });

    // 5. chart 应有 28 根连续 K 线 — 无断层 ✅
    expect(candles).toHaveLength(28);
    // 检查无任何 1m 间隔缺失（每两根 openTime 相差 60s）
    for (let i = 1; i < candles.length; i++) {
      const gap = Number(candles[i].time) - Number(candles[i - 1].time);
      expect(gap).toBe(60);  // 1m = 60s 严格连续
    }
  });

  it("upserts same-openTime kline (active → finalized 演进只保留最新)", () => {
    const next = reduceMarketMessages(
      { quotes: {}, quoteHistory: {}, klines: {} },
      [
        klineMsg("XAUUSD", "1m", "2026-05-26T18:11:00Z", "4521.00", false),  // active
        klineMsg("XAUUSD", "1m", "2026-05-26T18:11:00Z", "4521.50", true)    // finalized 覆盖
      ]
    );

    expect(next.klines.XAUUSD?.["1m"]).toHaveLength(1);
    expect(next.klines.XAUUSD?.["1m"]?.[0]?.close).toBe("4521.50");
    expect(next.klines.XAUUSD?.["1m"]?.[0]?.isFinal).toBe(true);
  });

  it("coalesces a batch into a single state update while preserving per-symbol history", () => {
    const next = reduceMarketMessages(
      { quotes: {}, quoteHistory: {}, klines: {} },
      [
        liveTick("XAUUSD", "4570.10", "2026-05-01T08:00:01Z"),
        liveTick("XAUUSD", "4570.20", "2026-05-01T08:00:02Z"),
        liveTick("BTCUSD", "76000.00", "2026-05-01T08:00:02Z")
      ]
    );

    expect(next.quotes.XAUUSD?.bid).toBe("4570.20");
    expect(next.quoteHistory.XAUUSD).toHaveLength(2);
    expect(next.quoteHistory.BTCUSD).toHaveLength(1);
  });
});

function klineMsg(symbol: string, interval: string, openTime: string, close: string, isFinal: boolean) {
  return {
    type: `kline.${interval}` as `kline.${"1m"}`,
    symbol,
    interval: interval as "1m",
    open: close,
    high: close,
    low: close,
    close,
    volume: "0",
    openTime,
    closeTime: openTime,  // 简化测试
    isFinal
  };
}

function liveTick(symbol: string, bid: string, ts: string) {
  const price = Number(bid);
  return {
    type: "price.tick" as const,
    symbol,
    bid,
    ask: String(price + 0.1),
    mid: String(price + 0.05),
    mark: String(price + 0.05),
    ts,
    receivedAt: ts,
    source: "TM_QUOTE",
    stale: false,
    quoteStatus: "FRESH"
  };
}
