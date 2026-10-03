import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PriceAlertCard } from "./PriceAlertCard";
import type { PriceAlertItem } from "./tradingTypes";

afterEach(() => cleanup());

const baseAlert: PriceAlertItem = {
  id: "alert-1",
  symbol: "BTCUSDT",
  direction: "ABOVE",
  targetPrice: "68000.00",
  status: "ACTIVE",
  note: "BTC 突破",
  basePrice: "67432.50",
  triggerCount: 1,
  remainingTriggers: 2,
  lastTriggeredAt: "2026-05-22T09:00:00Z",
  lastTriggeredPrice: "68010.00",
  cancelledAt: null,
  cancelSource: null,
  createdAt: "2026-05-22T08:00:00Z",
  updatedAt: "2026-05-22T09:00:00Z",
};

describe("PriceAlertCard", () => {
  it("renders symbol, direction (上穿) and status in row 1", () => {
    render(
      <PriceAlertCard
        item={baseAlert}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText(/上穿/)).toBeInTheDocument();
    expect(screen.getByText(/ACTIVE/)).toBeInTheDocument();
  });

  it("renders 下穿 label and fx-short class for BELOW direction", () => {
    const below = { ...baseAlert, direction: "BELOW" as const };
    const { container } = render(
      <PriceAlertCard item={below} onCancel={() => {}} cancelDisabled={false} />
    );
    expect(screen.getByText(/下穿/)).toBeInTheDocument();
    expect(container.querySelector(".fx-short")).not.toBeNull();
  });

  it("renders targetPrice + basePrice + trigger counts", () => {
    render(
      <PriceAlertCard
        item={baseAlert}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.getByText("68000.00")).toBeInTheDocument();
    expect(screen.getByText("67432.50")).toBeInTheDocument();
    expect(screen.getByText(/1.*3/)).toBeInTheDocument();
  });

  it("calls onCancel when cancel clicked (status ACTIVE)", async () => {
    const onCancel = vi.fn();
    render(
      <PriceAlertCard
        item={baseAlert}
        onCancel={onCancel}
        cancelDisabled={false}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /取消/ }));
    expect(onCancel).toHaveBeenCalledWith(baseAlert);
  });

  it("hides cancel button when status is not ACTIVE", () => {
    render(
      <PriceAlertCard
        item={{ ...baseAlert, status: "EXHAUSTED" }}
        onCancel={() => {}}
        cancelDisabled={false}
      />
    );
    expect(screen.queryByRole("button", { name: /取消/ })).toBeNull();
  });
});
