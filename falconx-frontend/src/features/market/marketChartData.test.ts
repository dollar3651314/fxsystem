import { describe, expect, it } from "vitest";
import {
  buildCandlesticks,
  buildQuoteLineData,
  buildTickLineData,
  buildTickQuoteLineData,
  resolveMarkupOffset
} from "./marketChartData";
import { CHART_TIMEFRAME_OPTIONS } from "./chartTimeframes";
import type { Kline, Quote } from "./marketTypes";

describe("marketChartData", () => {
  it("exposes only Tick plus backend-supported kline intervals", () => {
    expect(CHART_TIMEFRAME_OPTIONS.map((option) => option.value)).toEqual([
      "tick",
      "1m",
      "5m",
      "15m",
      "1h",
      "4h",
      "1d"
    ]);
  });

  it("does not synthesize tick quotes into local candlesticks", () => {
    const candles = buildCandlesticks({
      timeframe: "tick",
      klineHistory: [],
      liveKlines: []
    });

    expect(candles).toHaveLength(0);
  });

  it("keeps backend kline candles independent from quote ticks", () => {
    const historical: Kline = {
      symbol: "EURUSD",
      interval: "1m",
      open: "1.1680",
      high: "1.1682",
      low: "1.1678",
      close: "1.1681",
      volume: "10",
      openTime: "2026-04-30T07:30:00Z",
      closeTime: "2026-04-30T07:30:59Z",
      isFinal: false
    };

    const candles = buildCandlesticks({
      timeframe: "1m",
      klineHistory: [historical],
      liveKlines: []
    });

    expect(candles).toHaveLength(1);
    expect(candles[0]).toMatchObject({
      open: 1.168,
      high: 1.1682,
      low: 1.1678,
      close: 1.1681
    });
  });

  it("does not project latest live quote into backend kline candles", () => {
    const input: Parameters<typeof buildCandlesticks>[0] & { quote: Quote } = {
      timeframe: "1m",
      klineHistory: [
        {
          symbol: "XAGUSD",
          interval: "1m",
          open: "73.6100",
          high: "73.6400",
          low: "73.6000",
          close: "73.6200",
          volume: "20",
          openTime: "2026-05-01T08:12:00Z",
          closeTime: "2026-05-01T08:12:59Z",
          isFinal: false
        }
      ],
      liveKlines: [],
      quote: {
        symbol: "XAGUSD",
        bid: "73.6250",
        ask: "73.6670",
        mid: "73.6460",
        mark: "73.6460",
        ts: "2026-05-01T08:12:23Z",
        source: "TM_QUOTE",
        stale: false,
        priceStatus: "LIVE"
      }
    };
    const candles = buildCandlesticks(input);

    expect(candles).toHaveLength(1);
    expect(candles[0]).toMatchObject({
      open: 73.61,
      high: 73.64,
      low: 73.6,
      close: 73.62
    });
  });

  it("keeps active backend kline unchanged when quote history changes", () => {
    const input: Parameters<typeof buildCandlesticks>[0] & {
      quoteHistory: Quote[];
      quote: Quote;
    } = {
      timeframe: "1m",
      klineHistory: [
        {
          symbol: "AUDCAD",
          interval: "1m",
          open: "0.97800",
          high: "0.97810",
          low: "0.97790",
          close: "0.97802",
          volume: "10",
          openTime: "2026-05-01T08:12:00Z",
          closeTime: "2026-05-01T08:12:59Z",
          isFinal: false
        }
      ],
      liveKlines: [],
      quoteHistory: [
        quote("AUDCAD", "0.97820", "2026-05-01T08:12:12Z"),
        quote("AUDCAD", "0.97780", "2026-05-01T08:12:28Z")
      ],
      quote: quote("AUDCAD", "0.97850", "2026-05-01T08:12:44Z")
    };
    const candles = buildCandlesticks(input);

    expect(candles).toHaveLength(1);
    expect(candles[0]).toMatchObject({
      open: 0.978,
      high: 0.9781,
      low: 0.9779,
      close: 0.97802
    });
  });

  it("keeps all backend klines within the count window even when they have large time gaps", () => {
    const candles = buildCandlesticks({
      timeframe: "1m",
      klineHistory: [
        kline("GBPUSD", "2026-04-30T02:00:00Z", "1.3500"),
        kline("GBPUSD", "2026-04-30T10:15:00Z", "1.3496")
      ],
      liveKlines: []
    });

    expect(candles).toHaveLength(2);
    expect(candles[0]).toMatchObject({ time: 1777514400, close: 1.35 });
    expect(candles[1]).toMatchObject({ time: 1777544100, close: 1.3496 });
  });

  it("merges liveKlines into klineHistory and wss override same-openTime history (断层修复)", () => {
    // 2026-05-26 修复：wss 累积推送 5 根 K 线（含 1 根 openTime 与 history 重复 —— wss 优先）
    const candles = buildCandlesticks({
      timeframe: "1m",
      klineHistory: [
        kline("XAUUSD", "2026-05-26T18:10:00Z", "4520.00"),
        kline("XAUUSD", "2026-05-26T18:11:00Z", "4521.00"),
        kline("XAUUSD", "2026-05-26T18:12:00Z", "4522.00")
      ],
      liveKlines: [
        kline("XAUUSD", "2026-05-26T18:11:00Z", "4521.50"),  // 覆盖 history finalized 版
        kline("XAUUSD", "2026-05-26T18:13:00Z", "4523.00"),  // wss 新增 — 修复后必须出现
        kline("XAUUSD", "2026-05-26T18:14:00Z", "4524.00"),
        kline("XAUUSD", "2026-05-26T18:15:00Z", "4525.00"),
        kline("XAUUSD", "2026-05-26T18:16:00Z", "4526.00")
      ]
    });

    expect(candles).toHaveLength(7);
    expect(candles[1]).toMatchObject({ close: 4521.5 });   // wss 覆盖 history
    expect(candles[6]).toMatchObject({ close: 4526 });     // 18:16 在最后
  });

  it("builds tick line data from quote timestamps without minute bucketing", () => {
    const lineData = buildTickLineData(
      [
        quote("EURUSD", "1.1680", "2026-04-30T08:12:03Z"),
        quote("EURUSD", "1.1685", "2026-04-30T08:12:04Z")
      ],
      quote("EURUSD", "1.1690", "2026-04-30T08:12:05Z")
    );

    expect(lineData).toEqual([
      { time: 1777536723, value: 1.1679 },
      { time: 1777536724, value: 1.1684 },
      { time: 1777536725, value: 1.1689 }
    ]);
  });

  it("builds separate bid and ask quote lines", () => {
    const history = [
      quoteSpread("ADAEUR", "1.2583", "1.2585", "2026-05-11T05:42:55Z"),
      quoteSpread("ADAEUR", "1.2587", "1.2589", "2026-05-11T05:42:56Z")
    ];
    const latest = quoteSpread("ADAEUR", "1.2589", "1.2591", "2026-05-11T05:42:57Z");

    expect(buildQuoteLineData(history, latest, "bid")).toEqual([
      { time: 1778478175, value: 1.2583 },
      { time: 1778478176, value: 1.2587 },
      { time: 1778478177, value: 1.2589 }
    ]);
    expect(buildQuoteLineData(history, latest, "ask")).toEqual([
      { time: 1778478175, value: 1.2585 },
      { time: 1778478176, value: 1.2589 },
      { time: 1778478177, value: 1.2591 }
    ]);
  });

  it("uses quote tick history for Tick bid and ask lines", () => {
    const quoteHistory = [
      quoteSpread("AUDCAD", "0.99000", "0.99007", "2026-05-11T05:40:00Z"),
      quoteSpread("AUDCAD", "0.99010", "0.99018", "2026-05-11T05:41:00Z")
    ];
    const latest = quoteSpread("AUDCAD", "0.99020", "0.99029", "2026-05-11T05:42:00Z");

    expect(
      buildTickQuoteLineData({
        quoteHistory,
        quote: latest,
        side: "bid"
      })
    ).toEqual([
      { time: epoch("2026-05-11T05:40:00Z"), value: 0.99 },
      { time: epoch("2026-05-11T05:41:00Z"), value: 0.9901 },
      { time: epoch("2026-05-11T05:42:00Z"), value: 0.9902 }
    ]);

    expect(
      buildTickQuoteLineData({
        quoteHistory,
        quote: latest,
        side: "ask"
      })
    ).toEqual([
      { time: epoch("2026-05-11T05:40:00Z"), value: 0.99007 },
      { time: epoch("2026-05-11T05:41:00Z"), value: 0.99018 },
      { time: epoch("2026-05-11T05:42:00Z"), value: 0.99029 }
    ]);
  });

  it("does not use 1m klines to seed Tick line history", () => {
    const lineData = buildTickQuoteLineData({
      quoteHistory: [],
      quote: undefined,
      side: "bid"
    });

    expect(lineData).toEqual([]);
  });

  // ===== 2026-06-03 组加点平移：蜡烛整体平移 markupOffset（mid − baseMid）=====

  it("shifts candles by markupOffset so chart aligns with marked-up quotes", () => {
    const candles = buildCandlesticks({
      timeframe: "1m",
      klineHistory: [
        kline("XAUUSD", "2026-06-03T08:10:00Z", "4460.00"),
        kline("XAUUSD", "2026-06-03T08:11:00Z", "4461.00")
      ],
      liveKlines: [kline("XAUUSD", "2026-06-03T08:12:00Z", "4462.00")],
      markupOffset: 10 // 组加点 (bidExtra+askExtra)/2 = 10
    });

    expect(candles.map((c) => c.close)).toEqual([4470, 4471, 4472]);
    expect(candles[0]).toMatchObject({ open: 4470, high: 4470, low: 4470 });
  });

  it("defaults markupOffset to 0 keeping legacy base-price candles", () => {
    const candles = buildCandlesticks({
      timeframe: "1m",
      klineHistory: [kline("EURUSD", "2026-06-03T08:10:00Z", "1.1680")],
      liveKlines: []
    });

    expect(candles[0]).toMatchObject({ close: 1.168 });
  });

  it("resolveMarkupOffset derives offset from quote mid minus baseMid", () => {
    expect(
      resolveMarkupOffset({
        ...quoteSpread("XAUUSD", "4466.35", "4467.47", "2026-06-03T08:12:00Z"),
        baseMid: "4456.91",
        hasMarkup: true
      })
    ).toBeCloseTo(10, 8);
  });

  it("resolveMarkupOffset is 0 when baseMid missing, non-finite or quote undefined", () => {
    expect(resolveMarkupOffset(undefined)).toBe(0);
    expect(
      resolveMarkupOffset(quoteSpread("EURUSD", "1.1680", "1.1682", "2026-06-03T08:12:00Z"))
    ).toBe(0); // 未加点：无 baseMid 字段
    expect(
      resolveMarkupOffset({
        ...quoteSpread("EURUSD", "1.1680", "1.1682", "2026-06-03T08:12:00Z"),
        baseMid: "not-a-number"
      })
    ).toBe(0);
  });

  it("resolveMarkupOffset rounds float subtraction noise to 1e-8", () => {
    const offset = resolveMarkupOffset({
      ...quoteSpread("AUDCAD", "0.99186", "0.99191", "2026-06-03T08:12:00Z"),
      baseMid: "0.99088500" // mid 0.991885 − base 0.990885 = 0.001 精确
    });
    expect(offset).toBe(0.001);
  });

  it("keeps sub-second ticks distinct on the tick line", () => {
    const quoteHistory = [
      quoteSpread("AUDCAD", "0.99000", "0.99007", "2026-05-11T05:40:00.100Z"),
      quoteSpread("AUDCAD", "0.99010", "0.99018", "2026-05-11T05:40:00.500Z"),
      quoteSpread("AUDCAD", "0.99020", "0.99029", "2026-05-11T05:40:00.900Z")
    ];

    const bid = buildTickQuoteLineData({ quoteHistory, quote: undefined, side: "bid" });

    expect(bid).toHaveLength(3);
    expect(bid.map((p) => p.value)).toEqual([0.99, 0.9901, 0.9902]);
  });
});

function quote(symbol: string, mark: string, ts: string): Quote {
  const price = Number(mark);

  return {
    symbol,
    bid: String(price - 0.0001),
    ask: String(price + 0.0001),
    mid: mark,
    mark,
    ts,
    source: "TM_QUOTE",
    stale: false,
    priceStatus: "LIVE"
  };
}

function quoteSpread(symbol: string, bid: string, ask: string, ts: string): Quote {
  const mid = String((Number(bid) + Number(ask)) / 2);

  return {
    symbol,
    bid,
    ask,
    mid,
    mark: mid,
    ts,
    source: "TM_QUOTE",
    stale: false,
    priceStatus: "LIVE"
  };
}

function kline(symbol: string, openTime: string, close: string): Kline {
  return {
    symbol,
    interval: "1m",
    open: close,
    high: close,
    low: close,
    close,
    volume: "1",
    openTime,
    closeTime: openTime,
    isFinal: true
  };
}

function epoch(value: string): number {
  return Math.floor(Date.parse(value) / 1000);
}
