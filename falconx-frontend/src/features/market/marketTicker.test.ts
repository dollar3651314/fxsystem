import { describe, expect, it } from "vitest";
import { resolveTickerSymbols } from "./marketTicker";
import type { MarketSymbol } from "./marketTypes";

function sym(symbol: string, tradable = true): MarketSymbol {
  return { symbol, tradable };
}

describe("resolveTickerSymbols", () => {
  it("prefers主力品种 in preference order when present", () => {
    const symbols = [
      sym("AUDCAD"),
      sym("EURUSD"),
      sym("BTCUSD"),
      sym("XAUUSD")
    ];
    // 偏好顺序 BTCUSD > XAUUSD > EURUSD，AUDCAD 非偏好排后
    expect(resolveTickerSymbols(symbols, 3)).toEqual(["BTCUSD", "XAUUSD", "EURUSD"]);
  });

  it("backfills with列表顺序 tradable symbols when preferred不足", () => {
    const symbols = [sym("AUDCAD"), sym("BTCUSD"), sym("NZDCHF")];
    // 偏好仅命中 BTCUSD，其余按列表顺序补
    expect(resolveTickerSymbols(symbols, 3)).toEqual(["BTCUSD", "AUDCAD", "NZDCHF"]);
  });

  it("skips non-tradable symbols", () => {
    const symbols = [sym("BTCUSD", false), sym("EURUSD"), sym("AUDCAD")];
    expect(resolveTickerSymbols(symbols, 5)).toEqual(["EURUSD", "AUDCAD"]);
  });

  it("never exceeds max and dedupes", () => {
    const symbols = [sym("BTCUSD"), sym("ETHUSD"), sym("XAUUSD"), sym("EURUSD")];
    expect(resolveTickerSymbols(symbols, 2)).toHaveLength(2);
  });

  it("returns empty for empty input", () => {
    expect(resolveTickerSymbols([], 8)).toEqual([]);
  });
});
