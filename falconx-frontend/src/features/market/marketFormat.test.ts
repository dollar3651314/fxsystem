import { describe, expect, it } from "vitest";
import {
  formatMarketGroup,
  formatPriceStatusLabel,
  normalizePriceStatus,
  resolveAskPrice,
  resolveBidPrice,
  resolveDisplayPrice,
  resolveQuoteDisplayTimestamp,
  resolveQuoteTimestamp
} from "./marketFormat";

describe("marketFormat", () => {
  it("uses bid as the terminal display price and exposes bid ask separately", () => {
    const symbol = {
      symbol: "BTCUSD",
      bid: "76013.50",
      ask: "76014.00",
      mark: "76026.00"
    };
    const quote = {
      symbol: "BTCUSD",
      bid: "76020.50",
      ask: "76021.00",
      mid: "76020.75",
      mark: "76030.00",
      ts: "2026-05-01T08:00:00Z",
      source: "TM_QUOTE",
      stale: false,
      priceStatus: "LIVE" as const
    };

    // STAGE-12: 大字"当前价"业界标准用 mid (公允) 而非 bid，与 OrderTicket / K 线对齐
    expect(resolveDisplayPrice(symbol, quote)).toBe("76020.75");
    expect(resolveBidPrice(symbol, quote)).toBe("76020.50");
    expect(resolveAskPrice(symbol, quote)).toBe("76021.00");
    expect(resolveQuoteTimestamp(symbol, quote)).toBe("2026-05-01T08:00:00Z");
  });

  it("prefers frontend receive time for visible quote freshness", () => {
    expect(
      resolveQuoteDisplayTimestamp(
        { symbol: "BTCUSD", quoteTs: "2026-05-01T08:00:00Z" },
        {
          symbol: "BTCUSD",
          bid: "76020.50",
          ask: "76021.00",
          mid: "76020.75",
          mark: "76030.00",
          ts: "2026-05-01T08:00:01Z",
          receivedAt: "2026-05-01T08:00:02Z",
          source: "TM_QUOTE",
          stale: false,
          priceStatus: "LIVE"
        }
      )
    ).toBe("2026-05-01T08:00:02Z");
  });

  it("falls back to the symbol snapshot quote timestamp", () => {
    expect(
      resolveQuoteTimestamp(
        {
          symbol: "EURUSD",
          quoteTs: "2026-05-01T08:01:02Z"
        },
        undefined
      )
    ).toBe("2026-05-01T08:01:02Z");
  });

  it("prefers backend marketCode over numeric category", () => {
    expect(formatMarketGroup({ symbol: "EURUSD", category: 2, marketCode: "FX" })).toBe(
      "FX"
    );
  });

  it("maps backend quoteStatus values into UI price statuses", () => {
    expect(normalizePriceStatus("FRESH")).toBe("LIVE");
    expect(normalizePriceStatus("STALE")).toBe("REFERENCE");
    expect(normalizePriceStatus("NO_QUOTE")).toBe("REFERENCE");
  });

  it("formats backend price statuses as user-facing Chinese labels", () => {
    expect(formatPriceStatusLabel(true, "LIVE")).toBe("实时");
    expect(formatPriceStatusLabel(false, "REFERENCE")).toBe("参考价");
    expect(formatPriceStatusLabel(false, "MISSING")).toBe("缺失");
  });
});
