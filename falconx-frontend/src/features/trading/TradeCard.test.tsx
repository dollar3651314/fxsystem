import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { TradeCard } from "./TradeCard";
import type { TradeItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseTrade: TradeItem = {
  tradeId: "trade-1",
  orderId: "order-1",
  positionId: "pos-1",
  symbol: "BTCUSDT",
  side: "BUY",
  tradeType: "OPEN",
  quantity: "0.01",
  price: "67432.50",
  fee: "0.13",
  realizedPnl: null,
  tradedAt: "2026-05-22T09:00:00Z",
};

describe("TradeCard", () => {
  it("renders symbol, side, tradeType in row 1", () => {
    render(<TradeCard item={baseTrade} onDetail={() => {}} />);
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText(/OPEN/)).toBeInTheDocument();
  });

  it("renders price, quantity, fee", () => {
    render(<TradeCard item={baseTrade} onDetail={() => {}} />);
    expect(screen.getByText("67,432.50")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    // 手续费走 formatMoney 4 位
    expect(screen.getByText("0.1300")).toBeInTheDocument();
  });

  it("applies fx-long for positive realizedPnl, fx-short for negative", () => {
    const positive = { ...baseTrade, realizedPnl: "25.50" };
    const negative = { ...baseTrade, realizedPnl: "-12.30" };

    const { container: c1 } = render(<TradeCard item={positive} onDetail={() => {}} />);
    expect(c1.querySelector(".fx-long")).not.toBeNull();

    cleanup();

    const { container: c2 } = render(<TradeCard item={negative} onDetail={() => {}} />);
    expect(c2.querySelector(".fx-short")).not.toBeNull();
  });

  it("calls onDetail when card clicked", async () => {
    const onDetail = vi.fn();
    render(<TradeCard item={baseTrade} onDetail={onDetail} />);
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(baseTrade);
  });
});
