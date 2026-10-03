import { describe, expect, it } from "vitest";
import type { Kline } from "../marketTypes";
import { klineToBar } from "./tvDatafeed";

function makeKline(overrides: Partial<Kline> = {}): Kline {
  return {
    symbol: "EURUSD",
    interval: "1m",
    open: "1.10000",
    high: "1.10100",
    low: "1.09900",
    close: "1.10050",
    volume: "12.5",
    openTime: "2026-06-03T08:00:00Z",
    closeTime: "2026-06-03T08:01:00Z",
    isFinal: true,
    ...overrides
  };
}

describe("klineToBar", () => {
  it("Kline → TV Bar：time 为毫秒 UTC，OHLCV 数值化", () => {
    const bar = klineToBar(makeKline());
    expect(bar).toEqual({
      time: Date.parse("2026-06-03T08:00:00Z"),
      open: 1.1,
      high: 1.101,
      low: 1.099,
      close: 1.1005,
      volume: 12.5
    });
  });

  it("组加点 markupOffset 整体平移 OHLC（口径同经典图表 klineToCandle）", () => {
    const offset = 0.0003;
    const bar = klineToBar(makeKline(), offset);
    expect(bar?.open).toBeCloseTo(1.1003, 10);
    expect(bar?.high).toBeCloseTo(1.1013, 10);
    expect(bar?.low).toBeCloseTo(1.0993, 10);
    expect(bar?.close).toBeCloseTo(1.1008, 10);
  });

  it("openTime 不可解析或 OHLC 非有限值返回 null", () => {
    expect(klineToBar(makeKline({ openTime: "not-a-date" }))).toBeNull();
    expect(klineToBar(makeKline({ close: "abc" }))).toBeNull();
    expect(klineToBar(makeKline({ high: "Infinity" }))).toBeNull();
  });

  it("volume 非有限值时省略（TV Bar.volume 可选）", () => {
    const bar = klineToBar(makeKline({ volume: "n/a" }));
    expect(bar?.volume).toBeUndefined();
    expect(bar?.close).toBeCloseTo(1.1005, 10);
  });
});
