import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PendingOrderCard } from "./PendingOrderCard";
import type { PendingOrderItem } from "./tradingTypes";

afterEach(() => cleanup());

const basePending: PendingOrderItem = {
  id: "pending-1",
  orderNo: "PO-2026-001",
  symbol: "BTCUSDT",
  orderType: "STOP_LIMIT",
  side: "BUY",
  quantity: "0.01",
  triggerPrice: "67000.00",
  limitPrice: "67200.00",
  leverage: "50",
  marginMode: "ISOLATED",
  frozenMargin: "13.40",
  frozenFee: "0.05",
  status: "PENDING",
  parentPositionId: null,
  triggerKind: null,
  clientOrderId: null,
  triggeredOrderId: null,
  triggeredAt: null,
  cancelledAt: null,
  cancelReason: null,
  createdAt: "2026-05-22T09:00:00Z",
  updatedAt: "2026-05-22T09:00:00Z",
};

describe("PendingOrderCard", () => {
  it("renders symbol, side, orderType in row 1", () => {
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={() => {}}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText(/STOP_LIMIT/)).toBeInTheDocument();
  });

  it("renders trigger price, limit price, quantity, leverage in row 2", () => {
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={() => {}}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("67,000.00")).toBeInTheDocument();
    expect(screen.getByText("67,200.00")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("calls onDetail when card body is clicked", async () => {
    const onDetail = vi.fn();
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={onDetail}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    await userEvent.click(screen.getByText("BTCUSDT"));
    expect(onDetail).toHaveBeenCalledWith(basePending);
  });

  it("calls onEdit and onCancel without triggering onDetail", async () => {
    const onDetail = vi.fn();
    const onEdit = vi.fn();
    const onCancel = vi.fn();
    render(
      <PendingOrderCard
        item={basePending}
        onDetail={onDetail}
        onEdit={onEdit}
        onCancel={onCancel}
        cancelDisabled={false}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /修改/ }));
    expect(onEdit).toHaveBeenCalledWith(basePending);
    expect(onDetail).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: /撤销/ }));
    expect(onCancel).toHaveBeenCalledWith(basePending);
    expect(onDetail).not.toHaveBeenCalled();
  });

  it("hides edit/cancel buttons when status is not PENDING", () => {
    render(
      <PendingOrderCard
        item={{ ...basePending, status: "TRIGGERED" }}
        onDetail={() => {}}
        onEdit={() => {}}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.queryByRole("button", { name: /修改/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /撤销/ })).toBeNull();
  });
});
