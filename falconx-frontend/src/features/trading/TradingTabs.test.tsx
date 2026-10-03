import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { TradingTabs } from "./TradingTabs";

vi.mock("./PositionsTable", () => ({
  PositionsTable: () => <div>持仓列表内容</div>,
}));

vi.mock("./ClosedPositionsTable", () => ({
  ClosedPositionsTable: () => <div>历史列表内容</div>,
}));

vi.mock("./OrdersTable", () => ({
  OrdersTable: () => <div>订单列表内容</div>,
}));

vi.mock("./TradesTable", () => ({
  TradesTable: () => <div>成交列表内容</div>,
}));

vi.mock("./PendingOrdersTable", () => ({
  PendingOrdersTable: () => <div>挂单列表内容</div>,
}));

vi.mock("./PriceAlertsTable", () => ({
  PriceAlertsTable: () => <div>告警列表内容</div>,
}));

afterEach(() => cleanup());

describe("TradingTabs", () => {
  it("将当前交易列表暴露为独立滚动区", () => {
    render(<TradingTabs pnlMap={new Map()} socketState="open" serverTotalUnrealizedPnl={null} />);

    const positionsPanel = screen.getByRole("region", { name: "持仓列表滚动区" });
    expect(positionsPanel).toHaveClass("fx-tab-panel");
    expect(positionsPanel).toHaveTextContent("持仓列表内容");

    fireEvent.click(screen.getByRole("button", { name: /订单/ }));

    const ordersPanel = screen.getByRole("region", { name: "订单列表滚动区" });
    expect(ordersPanel).toHaveClass("fx-tab-panel");
    expect(ordersPanel).toHaveTextContent("订单列表内容");
  });
});
