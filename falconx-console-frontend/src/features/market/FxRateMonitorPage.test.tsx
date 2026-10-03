import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FxRateMonitorPage, STALE_THRESHOLD_MS } from "./FxRateMonitorPage";
import type { FxRate } from "./types";

// adminApi mock（透传层），由各用例按需设定返回。
const adminApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock("../../lib/api/apiClient", () => ({ adminApi: adminApiMock }));

// 权限 mock：useHasPermission。默认有 fx:view；按用例覆盖。
const hasPermissionMock = vi.hoisted(() => ({ fn: (): boolean => true }));
vi.mock("../../lib/auth/usePermission", () => ({
  useHasPermission: () => hasPermissionMock.fn,
}));

// 用固定相对偏移构造时间戳：旧（> 阈值）→ stale；新（远小于阈值）→ 实时。
// 不 mock Date.now，差值足够大（120s vs 1s）即便测试运行有几百 ms 抖动也稳定不 flaky。
const STALE_ROW: FxRate = {
  baseCurrency: "EUR",
  quoteCurrency: "USD",
  rate: "1.08321",
  eventTimeMillis: Date.now() - (STALE_THRESHOLD_MS + 60_000),
  sourceLpCode: "LP_A",
  sourceSymbol: "EURUSD",
};
const FRESH_ROW: FxRate = {
  baseCurrency: "GBP",
  quoteCurrency: "USD",
  rate: "1.26540",
  eventTimeMillis: Date.now() - 1_000,
  sourceLpCode: "LP_B",
  sourceSymbol: "GBPUSD",
};
const ROWS: FxRate[] = [STALE_ROW, FRESH_ROW];

function renderPage() {
  // 每个用例新建 client，关闭重试，避免污染与轮询尾随。
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <FxRateMonitorPage />
    </QueryClientProvider>,
  );
}

describe("FxRateMonitorPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.get.mockReset();
    hasPermissionMock.fn = () => true;
  });

  afterEach(() => cleanup());

  it("拉 FX rate 并渲染各货币对行", async () => {
    adminApiMock.get.mockResolvedValue(ROWS);
    renderPage();

    expect(await screen.findByText("FX 汇率监控")).toBeInTheDocument();
    expect(await screen.findByText("EUR/USD")).toBeInTheDocument();
    expect(await screen.findByText("GBP/USD")).toBeInTheDocument();
    expect(screen.getByText("1.08321")).toBeInTheDocument();
    // 来源列格式：sourceLpCode · sourceSymbol
    expect(screen.getByText("LP_A · EURUSD")).toBeInTheDocument();
  });

  it("GET 走 /admin/market/fx/rates", async () => {
    adminApiMock.get.mockResolvedValue(ROWS);
    renderPage();
    await waitFor(() => expect(adminApiMock.get).toHaveBeenCalled());
    expect(adminApiMock.get.mock.calls[0][0]).toBe("/admin/market/fx/rates");
  });

  it("旧 eventTimeMillis → 过期 badge，新 → 实时 badge", async () => {
    adminApiMock.get.mockResolvedValue(ROWS);
    renderPage();

    expect(await screen.findByText("EUR/USD")).toBeInTheDocument();
    // 一行过期、一行实时。
    expect(screen.getByText("过期")).toBeInTheDocument();
    expect(screen.getByText("实时")).toBeInTheDocument();
  });

  it("全部为新报价时无过期 badge", async () => {
    adminApiMock.get.mockResolvedValue([FRESH_ROW]);
    renderPage();

    expect(await screen.findByText("GBP/USD")).toBeInTheDocument();
    expect(screen.getByText("实时")).toBeInTheDocument();
    expect(screen.queryByText("过期")).not.toBeInTheDocument();
  });

  it("无 fx:view 权限时展示空态、不发请求", async () => {
    hasPermissionMock.fn = () => false;
    adminApiMock.get.mockResolvedValue(ROWS);
    renderPage();

    expect(await screen.findByText("无 fx:view 权限，无法查看 FX 汇率监控")).toBeInTheDocument();
    // enabled:false → queryFn 不执行。
    expect(adminApiMock.get).not.toHaveBeenCalled();
  });

  it("查询失败时显示 error Alert", async () => {
    adminApiMock.get.mockRejectedValue(new Error("network error"));
    renderPage();

    // 等待 error Alert 出现（异步，需 findBy）。
    expect(await screen.findByText("FX 汇率加载失败")).toBeInTheDocument();
    // 表格区域仍渲染（空数据），不会崩溃。
    expect(screen.queryByText("EUR/USD")).not.toBeInTheDocument();
  });

  it("来源列同时渲染 sourceLpCode 和 sourceSymbol", async () => {
    adminApiMock.get.mockResolvedValue(ROWS);
    renderPage();

    // 两行来源列各自显示 lpCode · symbol 格式。
    expect(await screen.findByText("LP_A · EURUSD")).toBeInTheDocument();
    expect(screen.getByText("LP_B · GBPUSD")).toBeInTheDocument();
  });
});
