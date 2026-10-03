import { afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { OrderTicketFab } from "./OrderTicketFab";

afterEach(() => cleanup());

describe("OrderTicketFab", () => {
  it("renders symbol and price when provided", () => {
    render(
      <OrderTicketFab
        symbol="BTCUSDT"
        priceLabel="67,432.50"
        onClick={() => {}}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("67,432.50")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /下单/ })).toBeEnabled();
  });

  it("renders disabled state when symbol is null", () => {
    render(
      <OrderTicketFab
        symbol={null}
        priceLabel={null}
        onClick={() => {}}
      />
    );
    const button = screen.getByRole("button");
    expect(button).toBeDisabled();
    expect(screen.getByText(/选择品种/)).toBeInTheDocument();
  });

  it("calls onClick when clicked with enabled state", async () => {
    const handle = vi.fn();
    render(
      <OrderTicketFab
        symbol="BTCUSDT"
        priceLabel="67,432.50"
        onClick={handle}
      />
    );
    await userEvent.click(screen.getByRole("button"));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("does not call onClick when disabled", async () => {
    const handle = vi.fn();
    render(
      <OrderTicketFab
        symbol={null}
        priceLabel={null}
        onClick={handle}
      />
    );
    await userEvent.click(screen.getByRole("button"));
    expect(handle).not.toHaveBeenCalled();
  });

  it("applies aria-label with symbol context", () => {
    render(
      <OrderTicketFab
        symbol="BTCUSDT"
        priceLabel="67,432.50"
        onClick={() => {}}
      />
    );
    expect(screen.getByRole("button", { name: /BTCUSDT.*下单/ })).toBeInTheDocument();
  });
});
