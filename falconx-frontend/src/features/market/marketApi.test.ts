import { afterEach, describe, expect, it, vi } from "vitest";
import { getKlines, getQuoteHistory, getSymbols } from "./marketApi";

describe("getSymbols", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns the symbols array from the backend response wrapper", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue({
      json: async () => ({
        code: "0",
        message: "success",
        data: {
          symbols: [
            {
              symbol: "EURUSD",
              marketCode: "FX",
              priceStatus: "REFERENCE",
              tradable: false
            }
          ]
        },
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "trace-1"
      })
    } as Response);

    await expect(getSymbols("access-token")).resolves.toEqual([
      {
        symbol: "EURUSD",
        marketCode: "FX",
        priceStatus: "REFERENCE",
        tradable: false
      }
    ]);
  });

  it("requests historical klines for the selected symbol", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      status: 200,
      json: async () => ({
        code: "0",
        message: "success",
        data: {
          klines: [
            {
              symbol: "EURUSD",
              interval: "1m",
              open: "1.0800",
              high: "1.0810",
              low: "1.0790",
              close: "1.0805",
              volume: "10",
              openTime: "2026-04-30T12:00:00Z",
              closeTime: "2026-04-30T12:00:59Z",
              isFinal: true
            }
          ]
        },
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "trace-2"
      })
    } as Response);

    await expect(getKlines("access-token", "EURUSD", "1m", 200)).resolves.toHaveLength(1);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/market/klines/EURUSD?interval=1m&limit=200"),
      expect.objectContaining({
        headers: expect.any(Headers)
      })
    );
  });

  it("requests historical quote ticks for the selected symbol", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue({
      status: 200,
      json: async () => ({
        code: "0",
        message: "success",
        data: {
          quotes: [
            {
              symbol: "AUDCAD",
              bid: "0.99010",
              ask: "0.99018",
              mid: "0.99014",
              mark: "0.99014",
              ts: "2026-05-11T05:41:00Z",
              source: "TM_QUOTE",
              stale: false,
              quoteStatus: "FRESH"
            }
          ]
        },
        timestamp: "2026-05-11T05:41:01Z",
        traceId: "trace-3"
      })
    } as Response);

    await expect(getQuoteHistory("access-token", "AUDCAD", 600)).resolves.toEqual([
      {
        symbol: "AUDCAD",
        bid: "0.99010",
        ask: "0.99018",
        mid: "0.99014",
        mark: "0.99014",
        ts: "2026-05-11T05:41:00Z",
        source: "TM_QUOTE",
        stale: false,
        quoteStatus: "FRESH",
        priceStatus: "LIVE"
      }
    ]);
    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining("/api/v1/market/quotes/AUDCAD/history?limit=600"),
      expect.objectContaining({
        headers: expect.any(Headers)
      })
    );
  });
});
