import { describe, expect, it } from "vitest";
import {
  ACCOUNT_CURRENCY,
  pnlColorClass,
  pnlSideClass,
  shouldShowQuoteRow,
} from "./pnlDisplay";

describe("pnlDisplay", () => {
  it("ACCOUNT_CURRENCY is the settlement token USDT", () => {
    expect(ACCOUNT_CURRENCY).toBe("USDT");
  });

  describe("pnlColorClass", () => {
    it("positive → fx-pnl-pos, negative → fx-pnl-neg, zero/null/NaN → ''", () => {
      expect(pnlColorClass("12.5")).toBe("fx-pnl-pos");
      expect(pnlColorClass("-3")).toBe("fx-pnl-neg");
      expect(pnlColorClass("0")).toBe("");
      expect(pnlColorClass(null)).toBe("");
      expect(pnlColorClass("abc")).toBe("");
    });
    it("accepts number directly (for numeric callers)", () => {
      expect(pnlColorClass(5.0)).toBe("fx-pnl-pos");
      expect(pnlColorClass(-2.5)).toBe("fx-pnl-neg");
      expect(pnlColorClass(0)).toBe("");
    });
  });

  describe("pnlSideClass", () => {
    it("positive → fx-long, negative → fx-short, zero/null → ''", () => {
      expect(pnlSideClass("0.01")).toBe("fx-long");
      expect(pnlSideClass("-0.01")).toBe("fx-short");
      expect(pnlSideClass("0")).toBe("");
      expect(pnlSideClass(undefined)).toBe("");
    });
  });

  describe("shouldShowQuoteRow", () => {
    it("omits sub-row when quote ccy equals account ccy (USDT)", () => {
      expect(shouldShowQuoteRow("USDT", "18.50")).toBe(false);
    });
    it("omits sub-row when quote ccy missing or inQuote missing", () => {
      expect(shouldShowQuoteRow(null, "18.50")).toBe(false);
      expect(shouldShowQuoteRow("", "18.50")).toBe(false);
      expect(shouldShowQuoteRow("AUD", null)).toBe(false);
    });
    it("shows sub-row for a real cross-currency position", () => {
      expect(shouldShowQuoteRow("AUD", "45.20")).toBe(true);
      expect(shouldShowQuoteRow("JPY", "-1200")).toBe(true);
    });
  });
});
