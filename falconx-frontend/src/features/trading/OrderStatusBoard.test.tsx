import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createElement, type ReactElement } from "react";
import { describe, expect, it } from "vitest";
import { OrderStatusBoard } from "./OrderStatusBoard";
import { composeEquity, marginStatusLabel, trimPercent } from "./orderStatusFormat";

function renderBoard(node: ReactElement) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, enabled: false } }
  });
  return render(createElement(QueryClientProvider, { client }, node));
}

describe("OrderStatusBoard", () => {
  it("renders WS-driven realtime fields (pnl/equity/marginLevel)", () => {
    renderBoard(
      createElement(OrderStatusBoard, {
        totalUnrealizedPnl: "-1487.93",
        accountEquity: "100705201.45",
        marginLevel: "11014986.05",
        marginLevelStatus: "HEALTHY",
        accountMarginMode: "ISOLATED"
      })
    );

    expect(screen.getByLabelText("订单数据看板")).toBeTruthy();
    // 亏损红色 tone + 带符号
    const pnl = screen.getByText(/-1,487\.93/);
    expect(pnl.className).toContain("order-status-board__value--down");
    // 保证金水平千分位 + 状态徽标
    expect(screen.getByText(/11,014,986\.05%/)).toBeTruthy();
    expect(screen.getByText("健康")).toBeTruthy();
    expect(screen.getByText("逐仓")).toBeTruthy();
  });

  it("falls back to em-dash before any WS push or REST data", () => {
    renderBoard(
      createElement(OrderStatusBoard, {
        totalUnrealizedPnl: null,
        accountEquity: null,
        marginLevel: null,
        marginLevelStatus: null,
        accountMarginMode: null
      })
    );

    // 持仓/挂单/盈亏/权益/可用/保证金水平 全部占位
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(6);
  });

  it("shows gain pnl with up tone", () => {
    renderBoard(
      createElement(OrderStatusBoard, {
        totalUnrealizedPnl: "12.5",
        accountEquity: null,
        marginLevel: "320.18",
        marginLevelStatus: "MARGIN_CALL",
        accountMarginMode: "CROSS"
      })
    );

    expect(screen.getByText(/\+12\.5/).className).toContain("order-status-board__value--up");
    expect(screen.getByText("预警")).toBeTruthy();
    expect(screen.getByText("全仓")).toBeTruthy();
  });
});

describe("trimPercent", () => {
  it("formats with thousand separators and 2 decimals", () => {
    expect(trimPercent("11014986.05")).toBe("11,014,986.05");
    expect(trimPercent("320.1")).toBe("320.10");
  });

  it("returns raw string for non-finite input", () => {
    expect(trimPercent("abc")).toBe("abc");
  });
});

describe("marginStatusLabel", () => {
  it("maps known statuses and passes through unknown", () => {
    expect(marginStatusLabel("HEALTHY")).toBe("健康");
    expect(marginStatusLabel("MARGIN_CALL")).toBe("预警");
    expect(marginStatusLabel("STOP_OUT")).toBe("强平");
    expect(marginStatusLabel("X")).toBe("X");
  });
});

describe("composeEquity", () => {
  it("sums balance and unrealized pnl (实时净值合成)", () => {
    expect(Number(composeEquity("100706511.07", "-1408.14"))).toBeCloseTo(100705102.93, 2);
    expect(composeEquity("1000", "12.5")).toBe("1012.5");
  });

  it("returns null when either source is missing or non-numeric", () => {
    expect(composeEquity(null, "-1408.14")).toBeNull();
    expect(composeEquity("1000", null)).toBeNull();
    expect(composeEquity("1000", undefined)).toBeNull();
    expect(composeEquity("abc", "1")).toBeNull();
  });
});
