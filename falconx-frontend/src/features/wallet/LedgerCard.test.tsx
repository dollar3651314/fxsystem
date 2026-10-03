import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { LedgerCard } from "./LedgerCard";
import type { LedgerEntry } from "./walletApi";

afterEach(() => cleanup());

const baseEntry: LedgerEntry = {
  ledgerId: "led-1",
  bizType: "DEPOSIT_CREDIT",
  amount: "100.00",
  idempotencyKey: null,
  referenceNo: "ref-001",
  balanceBefore: "1000.00",
  balanceAfter: "1100.00",
  frozenBefore: "0",
  frozenAfter: "0",
  marginUsedBefore: "0",
  marginUsedAfter: "0",
  createdAt: "2026-05-22T09:00:00Z",
};

describe("LedgerCard", () => {
  it("renders typeLabel + amount in row 1", () => {
    const { container } = render(<LedgerCard item={baseEntry} typeLabel="入金到账" currency="USDT" />);
    expect(screen.getByText("入金到账")).toBeInTheDocument();
    const amountEl = container.querySelector(".wallet-ledger-card__amount");
    // DEPOSIT_CREDIT 走 formatMoney 2 位
    expect(amountEl?.textContent).toMatch(/100\.00/);
  });

  it("renders createdAt + referenceNo + balanceAfter in row 2", () => {
    render(<LedgerCard item={baseEntry} typeLabel="入金到账" currency="USDT" />);
    expect(screen.getByText(/2026-05-22 09:00:00/)).toBeInTheDocument();
    expect(screen.getByText(/ref-001/)).toBeInTheDocument();
    expect(screen.getByText(/1,?100\.00/)).toBeInTheDocument();
  });

  it("applies fx-long class when balance increases", () => {
    const { container } = render(<LedgerCard item={baseEntry} typeLabel="入金到账" currency="USDT" />);
    expect(container.querySelector(".fx-long")).not.toBeNull();
  });

  it("applies fx-short class when balance decreases", () => {
    // 余额下降（提现）→ 红色，方向取自 balanceBefore→balanceAfter
    const neg = { ...baseEntry, amount: "50.00", balanceBefore: "1000.00", balanceAfter: "950.00" };
    const { container } = render(<LedgerCard item={neg} typeLabel="提现" currency="USDT" />);
    expect(container.querySelector(".fx-short")).not.toBeNull();
  });
});
