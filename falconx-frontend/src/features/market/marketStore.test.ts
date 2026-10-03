import { afterEach, describe, expect, it } from "vitest";
import { useMarketStore } from "./marketStore";

afterEach(() => {
  useMarketStore.getState().reset();
});

describe("useMarketStore", () => {
  it("clears selected symbol and cached market data on reset", () => {
    useMarketStore.getState().setSelectedSymbol("BTCUSD.p");
    useMarketStore.getState().applyMarketMessage({
      type: "price.tick",
      symbol: "BTCUSD.p",
      bid: "76013.50",
      ask: "76014.00",
      mid: "76013.75",
      mark: "76013.50",
      ts: "2026-05-01T07:30:00Z",
      source: "LP",
      stale: false,
      quoteStatus: "FRESH",
      qualityReason: null
    });

    useMarketStore.getState().reset();

    expect(useMarketStore.getState()).toMatchObject({
      selectedSymbol: null,
      connectionState: "idle",
      quotes: {},
      quoteHistory: {},
      klines: {}
    });
  });
});
