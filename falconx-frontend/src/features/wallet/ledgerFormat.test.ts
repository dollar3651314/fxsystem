import { describe, expect, it } from "vitest";
import { formatLedgerAmount, isLedgerFeeType, isLedgerPnlType } from "./ledgerFormat";

describe("formatLedgerAmount", () => {
  it("fee/swap types → 4 位金额", () => {
    expect(formatLedgerAmount("0.13456789", "ORDER_FEE_CHARGED", "USDT")).toBe("0.1345");
    expect(formatLedgerAmount("1.23456", "TRADE_FEE", "USDT")).toBe("1.2345");
    expect(formatLedgerAmount("2.5", "SWAP_CHARGE", "USDT")).toBe("2.5000");
    expect(formatLedgerAmount("3", "SWAP_INCOME", "USDT")).toBe("3.0000");
  });

  it("pnl types → 有效数字（signed 默认带 +）", () => {
    expect(formatLedgerAmount("12.3456", "REALIZED_PNL", "USDT")).toBe("+12.35");
    expect(formatLedgerAmount("-0.00216674", "LIQUIDATION_PNL", "USDT")).toBe("-0.0022");
    expect(formatLedgerAmount("0", "POSITION_CLOSE_PNL", "USDT")).toBe("0.00");
  });

  it("pnl types signed=false → 量级无符号（供调用方加方向符号）", () => {
    expect(formatLedgerAmount("12.3456", "REALIZED_PNL", "USDT", false)).toBe("12.35");
    expect(formatLedgerAmount("0.00216674", "LIQUIDATION_PNL", "USDT", false)).toBe("0.0022");
  });

  it("其余（保证金/冻结/入金/出金/调整）→ 币种精度（默认 2）", () => {
    expect(formatLedgerAmount("100.00000000", "DEPOSIT_CREDIT", "USDT")).toBe("100.00");
    expect(formatLedgerAmount("5.5", "ORDER_MARGIN_RESERVED", "USDT")).toBe("5.50");
    expect(formatLedgerAmount("250.987", "ADMIN_BALANCE_ADJUST", "USDT")).toBe("250.98");
    expect(formatLedgerAmount("1234.5", "WITHDRAW_SETTLE", "USDT")).toBe("1,234.50");
  });

  it("分类断言 helpers", () => {
    expect(isLedgerFeeType("ORDER_FEE_CHARGED")).toBe(true);
    expect(isLedgerFeeType("REALIZED_PNL")).toBe(false);
    expect(isLedgerPnlType("LIQUIDATION_PNL")).toBe(true);
    expect(isLedgerPnlType("DEPOSIT_CREDIT")).toBe(false);
  });
});
