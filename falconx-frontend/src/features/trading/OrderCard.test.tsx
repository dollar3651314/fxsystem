import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { OrderCard } from "./OrderCard";
import type { OrderItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseOrder: OrderItem = {
  orderId: "order-1",
  orderNo: "ORD-2026-001",
  symbol: "BTCUSDT",
  side: "BUY",
  orderType: "MARKET",
  quantity: "0.01",
  requestedPrice: null,
  filledPrice: "67432.50",
  leverage: "50",
  margin: "5.50",
  fee: "0.13",
  status: "FILLED",
  rejectReason: null,
  clientOrderId: "c-1",
  createdAt: "2026-05-22T09:00:00Z",
  updatedAt: "2026-05-22T09:00:01Z",
};

describe("OrderCard", () => {
  it("renders symbol, side, orderType, status in row 1", () => {
    render(<OrderCard item={baseOrder} onDetail={() => {}} />);
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText(/MARKET/)).toBeInTheDocument();
    expect(screen.getByText(/FILLED/)).toBeInTheDocument();
  });

  it("renders filled price, quantity, leverage", () => {
    render(<OrderCard item={baseOrder} onDetail={() => {}} />);
    expect(screen.getByText("67,432.50")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("applies fx-long class for BUY side", () => {
    const { container } = render(<OrderCard item={baseOrder} onDetail={() => {}} />);
    expect(container.querySelector(".fx-long")).not.toBeNull();
  });

  it("calls onDetail when card clicked", async () => {
    const onDetail = vi.fn();
    render(<OrderCard item={baseOrder} onDetail={onDetail} />);
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(baseOrder);
  });
});
