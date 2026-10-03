import { describe, expect, it } from "vitest";
import { groupMarketSymbols, resolveMarketGroupSymbol } from "./marketSymbolGroups";

describe("groupMarketSymbols", () => {
  it("keeps quoted and tradable products above missing quote variants", () => {
    const groups = groupMarketSymbols([
      { symbol: "XAGUSD.f", category: 3, priceStatus: "MISSING", tradable: false },
      { symbol: "XAUUSD", category: 3, priceStatus: "LIVE", tradable: true },
      { symbol: "XAGUSD", category: 3, priceStatus: "LIVE", tradable: true },
      { symbol: "XAUUSD.p", category: 3, priceStatus: "MISSING", tradable: false },
    ]);

    expect(groups[0].label).toBe("METAL");
    expect(groups[0].symbols.map((symbol) => symbol.symbol)).toEqual([
      "XAGUSD",
      "XAUUSD",
      "XAGUSD.f",
      "XAUUSD.p",
    ]);
  });
});

describe("resolveMarketGroupSymbol", () => {
  it("defaults the selected symbol to the active group instead of the full symbol list", () => {
    const groups = groupMarketSymbols([
      { symbol: "ADAUSD", marketCode: "CRYPTO", priceStatus: "REFERENCE", tradable: false },
      { symbol: "AUDCAD", marketCode: "FX", priceStatus: "LIVE", tradable: true },
      { symbol: "AUDCHF", marketCode: "FX", priceStatus: "LIVE", tradable: true }
    ]);

    expect(resolveMarketGroupSymbol(groups, "FX", "ADAUSD")).toBe("AUDCAD");
  });

  it("keeps the current symbol when it belongs to the active group", () => {
    const groups = groupMarketSymbols([
      { symbol: "AUDCAD", marketCode: "FX", priceStatus: "LIVE", tradable: true },
      { symbol: "AUDCHF", marketCode: "FX", priceStatus: "LIVE", tradable: true }
    ]);

    expect(resolveMarketGroupSymbol(groups, "FX", "AUDCHF")).toBe("AUDCHF");
  });
});
