import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ClosedPositionCard } from "./ClosedPositionCard";
import type { PositionItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseClosed: PositionItem = {
  positionId: "pos-closed-1",
  openingOrderId: "ord-1",
  symbol: "BTCUSDT",
  side: "BUY",
  quantity: "0.01",
  entryPrice: "67432.50",
  leverage: "50",
  margin: "5.50",
  marginMode: "ISOLATED",
  liquidationPrice: null,
  takeProfitPrice: null,
  stopLossPrice: null,
  markPrice: null,
  quoteCurrency: "USDT",
  unrealizedPnlInQuote: null,
  unrealizedPnlInAccount: null,
  isolatedMargin: null,
  closePrice: "67500.00",
  closeReason: "USER_CLOSE",
  realizedPnl: "12.30",
  status: "CLOSED",
  quoteStale: null,
  quoteTs: null,
  quoteSource: null,
  openedAt: "2026-05-22T09:00:00Z",
  closedAt: "2026-05-22T10:00:00Z",
  updatedAt: "2026-05-22T10:00:00Z",
  openFee: "0.13",
  openFeeRate: "0.0002",
  groupCodeAtOpen: "GROUP-1",
  bidExtraAtOpen: "0.0001",
  askExtraAtOpen: "0.0001",
  effectiveMarkPrice: "67450.00",
};

describe("ClosedPositionCard", () => {
  it("renders symbol, side, quantity, leverage in row 1", () => {
    render(<ClosedPositionCard item={baseClosed} onDetail={() => {}} />);
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("renders entry price → close price in row 2", () => {
    render(<ClosedPositionCard item={baseClosed} onDetail={() => {}} />);
    expect(screen.getByText("67,432.50")).toBeInTheDocument();
    expect(screen.getByText("67,500.00")).toBeInTheDocument();
  });

  it("applies fx-long class for positive realizedPnl", () => {
    const { container } = render(<ClosedPositionCard item={baseClosed} onDetail={() => {}} />);
    const pnlEl = container.querySelector(".fx-long");
    expect(pnlEl).not.toBeNull();
  });

  it("calls onDetail when card clicked", async () => {
    const onDetail = vi.fn();
    render(<ClosedPositionCard item={baseClosed} onDetail={onDetail} />);
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(baseClosed);
  });
});
