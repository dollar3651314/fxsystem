import { afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PositionItemCard } from "./PositionItemCard";
import type { PositionItem } from "./tradingTypes";

afterEach(() => cleanup());

const basePosition: PositionItem = {
  positionId: "pos-abc-123",
  openingOrderId: "ord-1",
  symbol: "BTCUSDT",
  side: "BUY",
  quantity: "0.01",
  entryPrice: "67432.50",
  leverage: "50",
  margin: "5.50",
  marginMode: "ISOLATED",
  liquidationPrice: "61580.00",
  takeProfitPrice: "68500.00",
  stopLossPrice: "66800.00",
  markPrice: "67450.00",
  quoteCurrency: "USDT",
  unrealizedPnlInQuote: "18.50",
  unrealizedPnlInAccount: "18.50",
  isolatedMargin: "5.50",
  closePrice: null,
  closeReason: null,
  realizedPnl: "12.30",
  status: "OPEN",
  quoteStale: false,
  quoteTs: null,
  quoteSource: null,
  openedAt: "2026-05-20T09:00:00Z",
  closedAt: null,
  updatedAt: "2026-05-22T09:00:00Z",
  openFee: "0.13",
  openFeeRate: "0.0002",
  groupCodeAtOpen: "GROUP-1",
  bidExtraAtOpen: "0.0001",
  askExtraAtOpen: "0.0001",
  effectiveMarkPrice: "67450.00",
};

describe("PositionItemCard", () => {
  it("renders symbol, side, quantity, leverage in row 1", () => {
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("多")).toBeInTheDocument();
    expect(screen.getByText("0.01")).toBeInTheDocument();
    expect(screen.getByText("50x")).toBeInTheDocument();
  });

  it("renders entry and mark prices in row 2", () => {
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    // 价格经 formatPrice（缺省精度回退 2 位 + 千分位）格式化
    expect(screen.getByText("67,432.50")).toBeInTheDocument();
    expect(screen.getByText("67,450.00")).toBeInTheDocument();
  });

  it("applies fx-long class for positive pnl side", () => {
    const { container } = render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(container.querySelector(".fx-long")).not.toBeNull();
  });

  it("calls onClose when 平仓 button clicked", async () => {
    const handle = vi.fn();
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={handle}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /平仓/ }));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("calls onAddMargin and onEditRiskControls from More menu", async () => {
    const onAddMargin = vi.fn();
    const onEditRiskControls = vi.fn();
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={onAddMargin}
        onEditRiskControls={onEditRiskControls}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /更多操作/ }));
    await userEvent.click(screen.getByRole("button", { name: /补保/ }));
    expect(onAddMargin).toHaveBeenCalledOnce();
    await userEvent.click(screen.getByRole("button", { name: /更多操作/ }));
    await userEvent.click(screen.getByRole("button", { name: /改 TP\/SL/ }));
    expect(onEditRiskControls).toHaveBeenCalledOnce();
  });

  it("uses patch.unrealizedPnlInAccount when pnl override is provided", () => {
    render(
      <PositionItemCard
        position={basePosition}
        pnl={{
          positionId: "pos-abc-123",
          unrealizedPnlInAccount: "25.99",
          markPrice: "67455.00",
        } as unknown as Parameters<typeof PositionItemCard>[0]["pnl"]}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(screen.getByText("25.99")).toBeInTheDocument();
    expect(screen.getByText("67,455.00")).toBeInTheDocument();
  });

  it("omits quote sub-row when settlement ccy is the account ccy (USDT)", () => {
    // basePosition.quoteCurrency === 'USDT' === 账户币 → inQuote 与 inAccount 同值，副行省略
    render(
      <PositionItemCard
        position={basePosition}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    // 主行账户币标注存在
    expect(screen.getByText("USDT")).toBeInTheDocument();
    // 不渲染报价币副行（无 "<quote> AUD" 之类）
    expect(screen.queryByText(/AUD/)).toBeNull();
  });

  it("renders dual-currency rows for a cross-currency position (账户币主 + 报价币副)", () => {
    const crossCcy: PositionItem = {
      ...basePosition,
      symbol: "AUDUSD",
      quoteCurrency: "AUD",
      unrealizedPnlInAccount: "30.00", // USDT 主
      unrealizedPnlInQuote: "45.20", // AUD 副
    };
    const { container } = render(
      <PositionItemCard
        position={crossCcy}
        pnl={undefined}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    // 主行：账户币值 + USDT 标注
    expect(screen.getByText("30.00")).toBeInTheDocument();
    expect(screen.getByText("USDT")).toBeInTheDocument();
    // 副行：报价币值 + AUD 标注（同一 span 内 "45.20 AUD"）
    expect(screen.getByText(/45\.20\s+AUD/)).toBeInTheDocument();
    // 盈亏色：正值用 fx-long
    expect(container.querySelector(".fx-position-card__row3 .fx-long")).not.toBeNull();
  });

  it("prefers patch dual-currency fields over position fields", () => {
    const crossCcy: PositionItem = {
      ...basePosition,
      quoteCurrency: "AUD",
      unrealizedPnlInAccount: "30.00",
      unrealizedPnlInQuote: "45.20",
    };
    render(
      <PositionItemCard
        position={crossCcy}
        pnl={{
          positionId: crossCcy.positionId,
          unrealizedPnlInAccount: "31.50",
          unrealizedPnlInQuote: "47.10",
          quoteCurrency: "AUD",
          markPrice: "67460.00",
        } as unknown as Parameters<typeof PositionItemCard>[0]["pnl"]}
        onDetail={() => {}}
        onClose={() => {}}
        onAddMargin={() => {}}
        onEditRiskControls={() => {}}
      />
    );
    expect(screen.getByText("31.50")).toBeInTheDocument();
    expect(screen.getByText(/47\.10\s+AUD/)).toBeInTheDocument();
  });
});
