import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MarketWatchlist } from "./MarketWatchlist";
import { groupMarketSymbols } from "./marketSymbolGroups";
import type { MarketSymbol } from "./marketTypes";

afterEach(() => cleanup());

describe("MarketWatchlist", () => {
  it("groups all symbols by market category without dropping rows", () => {
    const groups = groupMarketSymbols([
      symbol("BTCUSD.p", "CRYPTO", 1),
      symbol("EURUSD", "FX", 2),
      symbol("XAUUSD", "METAL", 3),
      symbol("GBPUSD", "FX", 2)
    ]);

    expect(groups.map((group) => [group.label, group.symbols.map((item) => item.symbol)])).toEqual([
      ["FX", ["EURUSD", "GBPUSD"]],
      ["METAL", ["XAUUSD"]],
      ["CRYPTO", ["BTCUSD.p"]]
    ]);
    expect(groups.flatMap((group) => group.symbols)).toHaveLength(4);
  });

  it("renders one active category and switches horizontally", async () => {
    const groups = groupMarketSymbols([
      symbol("BTCUSD.p", "CRYPTO", 1),
      symbol("EURUSD", "FX", 2),
      symbol("XAUUSD", "METAL", 3),
      symbol("GBPUSD", "FX", 2)
    ]);
    const user = userEvent.setup();
    const onSelectGroup = vi.fn();

    render(
      <MarketWatchlist
        groups={groups}
        activeGroupKey="FX"
        quotes={{}}
        selectedSymbol="EURUSD"
        isLoading={false}
        onSelectSymbol={() => undefined}
        onSelectGroup={onSelectGroup}
      />
    );

    expect(screen.getAllByRole("tab")).toHaveLength(3);
    expect(screen.getByRole("tab", { name: /FX/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("button")).toHaveLength(2);
    expect(screen.getByRole("button", { name: /GBPUSD/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /BTCUSD\.p/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: /CRYPTO/ }));

    expect(onSelectGroup).toHaveBeenCalledWith("CRYPTO");
  });

  it("renders live bid and ask values from websocket quotes", () => {
    const groups = groupMarketSymbols([symbol("BTCUSD", "CRYPTO", 1)]);

    render(
      <MarketWatchlist
        groups={groups}
        activeGroupKey="CRYPTO"
        quotes={{
          BTCUSD: {
            symbol: "BTCUSD",
            bid: "76013.50",
            ask: "76014.00",
            mid: "76013.75",
            mark: "76026.00",
            ts: "2026-05-01T08:00:00Z",
            receivedAt: "2026-05-01T08:00:01Z",
            source: "TM_QUOTE",
            stale: false,
            priceStatus: "LIVE"
          }
        }}
        selectedSymbol="BTCUSD"
        isLoading={false}
        onSelectSymbol={() => undefined}
        onSelectGroup={() => undefined}
      />
    );

    const row = screen.getByRole("button", { name: /BTCUSD/ });
    expect(within(row).getByText("Bid")).toBeInTheDocument();
    expect(within(row).getByText("Ask")).toBeInTheDocument();
    expect(within(row).getByText("76,013.50")).toBeInTheDocument();
    expect(within(row).getByText("76,014.00")).toBeInTheDocument();
    expect(within(row).getByText(/收到 \d{2}:\d{2}:\d{2}/)).toBeInTheDocument();
    expect(within(row).queryByText("76,026.00")).not.toBeInTheDocument();
  });

  it("reports a bounded visible fallback subscription set when observers are unavailable", async () => {
    const groups = groupMarketSymbols(
      Array.from({ length: 25 }, (_, index) => symbol(`FX${String(index).padStart(2, "0")}`, "FX", 2))
    );
    const onVisibleSymbolsChange = vi.fn();

    render(
      <MarketWatchlist
        groups={groups}
        activeGroupKey="FX"
        quotes={{}}
        selectedSymbol="FX0"
        isLoading={false}
        onSelectSymbol={() => undefined}
        onSelectGroup={() => undefined}
        onVisibleSymbolsChange={onVisibleSymbolsChange}
      />
    );

    await waitFor(() => {
      expect(onVisibleSymbolsChange).toHaveBeenCalledWith(
        Array.from({ length: 20 }, (_, index) => `FX${String(index).padStart(2, "0")}`)
      );
    });
  });

  it("renders a bounded virtual window for high-volume categories", () => {
    const groups = groupMarketSymbols(
      Array.from({ length: 1040 }, (_, index) =>
        symbol(`A${String(index).padStart(4, "0")}.NYS`, "US_STOCK", 6)
      )
    );

    const { container } = render(
      <MarketWatchlist
        groups={groups}
        activeGroupKey="US_STOCK"
        quotes={{}}
        selectedSymbol="A0000.NYS"
        isLoading={false}
        onSelectSymbol={() => undefined}
        onSelectGroup={() => undefined}
      />
    );

    expect(container.querySelector(".market-list__virtual-spacer")).toBeInTheDocument();
    expect(container.querySelectorAll(".market-row").length).toBeLessThanOrEqual(40);
  });

  it("shows user-facing Chinese labels for non-live price states", () => {
    const groups = groupMarketSymbols([
      symbol("AUDCAD", "FX", 2),
      { ...symbol("BROKEN", "FX", 2), priceStatus: "MISSING" }
    ]);

    render(
      <MarketWatchlist
        groups={groups}
        activeGroupKey="FX"
        quotes={{}}
        selectedSymbol="AUDCAD"
        isLoading={false}
        onSelectSymbol={() => undefined}
        onSelectGroup={() => undefined}
      />
    );

    expect(screen.getByText("参考价")).toBeInTheDocument();
    expect(screen.getByText("缺失")).toBeInTheDocument();
    expect(screen.queryByText("REFERENCE")).not.toBeInTheDocument();
    expect(screen.queryByText("MISSING")).not.toBeInTheDocument();
  });
});

function symbol(symbolName: string, marketCode: string, category: number): MarketSymbol {
  return {
    symbol: symbolName,
    marketCode,
    category,
    priceStatus: "REFERENCE",
    tradable: false
  };
}
