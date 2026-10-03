import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { TradingExposureBoardPage } from "./TradingExposureBoardPage";
import { aggregateByQuoteCurrency, type ExposureByQuoteRow } from "./exposureAggregate";
import type { ExposureItem } from "./types";
import type { AdminExposureUpdate } from "./useAdminTradingSocket";

// 捕获最近一次传给 useAdminTradingSocket 的 handlers，测试可主动触发 onExposureUpdate。
let lastHandlers: { onExposureUpdate?: (u: AdminExposureUpdate) => void } = {};
vi.mock("./useAdminTradingSocket", () => ({
  useAdminTradingSocket: (_t: string | null, h: typeof lastHandlers) => {
    lastHandlers = h;
    return "open";
  },
}));
vi.mock("../../lib/auth/adminTokenStorage", () => ({
  getAdminAccessToken: () => "test-admin-token",
}));
vi.mock("../../lib/auth/adminAuthStore", () => ({
  useAdminAuthStore: (selector: (s: { user: { id: string } }) => unknown) =>
    selector({ user: { id: "1" } }),
}));

const listExposuresMock = vi.hoisted(() => vi.fn());
vi.mock("./tradingApi", () => ({
  tradingApi: { listExposures: listExposuresMock },
}));

const ROWS: ExposureItem[] = [
  { symbol: "BTCUSD", quoteCurrency: "USD", totalLongQty: "10", totalShortQty: "4", netExposure: "6", netExposureUsd: "300000", updatedAt: "2026-06-01T10:00:00Z" },
  { symbol: "ETHUSD", quoteCurrency: "USD", totalLongQty: "2", totalShortQty: "5", netExposure: "-3", netExposureUsd: "-100000", updatedAt: "2026-06-01T10:00:01Z" },
  { symbol: "USDJPY", quoteCurrency: "JPY", totalLongQty: "1", totalShortQty: "0", netExposure: "1", netExposureUsd: "50000", updatedAt: "2026-06-01T10:00:02Z" },
];

describe("aggregateByQuoteCurrency", () => {
  it("按 quoteCurrency 分组并用 netExposureUsd 求和（USD 等价）", () => {
    const rows = aggregateByQuoteCurrency(ROWS);
    const byQc = (qc: string) => rows.find((r) => r.quoteCurrency === qc) as ExposureByQuoteRow;

    // USD 组：300000 + (-100000) = 200000；多头 300000，空头 100000，2 个 symbol
    expect(byQc("USD").netExposureUsd).toBe(200000);
    expect(byQc("USD").longExposureUsd).toBe(300000);
    expect(byQc("USD").shortExposureUsd).toBe(100000);
    expect(byQc("USD").symbolCount).toBe(2);

    // JPY 组：50000，1 个 symbol
    expect(byQc("JPY").netExposureUsd).toBe(50000);
    expect(byQc("JPY").symbolCount).toBe(1);
  });

  it("占比按 |netExposureUsd| 归一（Σ|net| = 200000 + 50000）", () => {
    const rows = aggregateByQuoteCurrency(ROWS);
    const total = 200000 + 50000;
    const usd = rows.find((r) => r.quoteCurrency === "USD")!;
    const jpy = rows.find((r) => r.quoteCurrency === "JPY")!;
    expect(usd.share).toBeCloseTo(200000 / total, 6);
    expect(jpy.share).toBeCloseTo(50000 / total, 6);
    // 降序：|200000| 在前
    expect(rows[0].quoteCurrency).toBe("USD");
  });

  it("quoteCurrency 缺失归入「未知」组，netExposureUsd 非数按 0 计", () => {
    const rows = aggregateByQuoteCurrency([
      { symbol: "X", quoteCurrency: null, totalLongQty: "0", totalShortQty: "0", netExposure: "0", netExposureUsd: "7", updatedAt: "" },
      { symbol: "Y", quoteCurrency: "", totalLongQty: "0", totalShortQty: "0", netExposure: "0", netExposureUsd: "not-a-number", updatedAt: "" },
    ]);
    const unknown = rows.find((r) => r.quoteCurrency === "未知")!;
    expect(unknown.symbolCount).toBe(2);
    expect(unknown.netExposureUsd).toBe(7);
  });

  it("空列表返回空数组，不除零", () => {
    expect(aggregateByQuoteCurrency([])).toEqual([]);
  });
});

describe("TradingExposureBoardPage", () => {
  beforeEach(() => {
    lastHandlers = {};
    listExposuresMock.mockReset();
    listExposuresMock.mockResolvedValue({ items: ROWS });
  });
  afterEach(() => cleanup());

  // AntD Table 会渲染 measure 行导致表头/单元格文本重复，用 *AllByText 取首个匹配即可。
  it("按 Symbol tab（默认）渲染 per-symbol 行 + 报价币列，不回归", async () => {
    render(<TradingExposureBoardPage />);
    expect((await screen.findAllByText("BTCUSD")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("ETHUSD").length).toBeGreaterThan(0);
    expect(screen.getAllByText("USDJPY").length).toBeGreaterThan(0);
    // 原 per-symbol 列保留
    expect(screen.getAllByText("净敞口（基础币）").length).toBeGreaterThan(0);
    expect(screen.getAllByText("净敞口（USD）").length).toBeGreaterThan(0);
  });

  it("切到「按报价币聚合」tab 显示按 quoteCurrency 汇总的 USD 等价净敞口", async () => {
    render(<TradingExposureBoardPage />);
    await screen.findAllByText("BTCUSD");

    fireEvent.click(screen.getByRole("tab", { name: "按报价币聚合" }));

    // 聚合表头出现
    expect((await screen.findAllByText("净敞口（USD 等价）")).length).toBeGreaterThan(0);
    // USD 组净敞口 +200,000.00、JPY 组 +50,000.00（统一精度：带符号 USD 金额，千分位）
    expect(screen.getAllByText("+200,000.00").length).toBeGreaterThan(0);
    expect(screen.getAllByText("+50,000.00").length).toBeGreaterThan(0);
  });

  it("admin.exposure.update WS patch 消费 quoteCurrency（实时合入聚合）", async () => {
    render(<TradingExposureBoardPage />);
    await screen.findAllByText("BTCUSD");

    // 推一个新 symbol（EUR 组）
    act(() => {
      lastHandlers.onExposureUpdate?.({
        symbol: "EURUSD",
        quoteCurrency: "EUR",
        totalLongQty: "5",
        totalShortQty: "0",
        netExposure: "5",
        netExposureUsd: "80000",
        quoteTs: "2026-06-01T10:05:00Z",
      });
    });

    // per-symbol tab 出现新行
    expect((await screen.findAllByText("EURUSD")).length).toBeGreaterThan(0);

    // 切聚合 tab，应出现 EUR 组 +80,000.00（统一精度：带符号 USD 金额，千分位）
    fireEvent.click(screen.getByRole("tab", { name: "按报价币聚合" }));
    await waitFor(() => expect(screen.getAllByText("+80,000.00").length).toBeGreaterThan(0));
  });
});
