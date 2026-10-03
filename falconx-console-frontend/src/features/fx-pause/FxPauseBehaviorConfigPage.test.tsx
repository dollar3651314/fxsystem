import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { FxPauseBehaviorConfigPage } from "./FxPauseBehaviorConfigPage";
import type { FxPauseBehavior } from "./types";

// adminApi mock（透传层），由各用例按需设定返回。
const adminApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock("../../lib/api/apiClient", () => ({ adminApi: adminApiMock }));

// 权限 mock：RequiresPermission → useHasPermission。默认有 fx:pause-behavior:edit；按用例覆盖。
const hasPermissionMock = vi.hoisted(() => ({ fn: (): boolean => true }));
vi.mock("../../lib/auth/usePermission", () => ({
  useHasPermission: () => hasPermissionMock.fn,
}));

// 8 行夹具：含 forex(2) allowOpen:false 以验证只读展示与编辑回填。
const EIGHT_ROWS: FxPauseBehavior[] = [
  { category: 1, categoryName: "crypto", allowOpen: true, allowClose: true, allowLiquidation: true },
  { category: 2, categoryName: "forex", allowOpen: false, allowClose: true, allowLiquidation: false },
  { category: 3, categoryName: "metal", allowOpen: true, allowClose: true, allowLiquidation: true },
  { category: 4, categoryName: "index", allowOpen: true, allowClose: true, allowLiquidation: true },
  { category: 5, categoryName: "energy", allowOpen: true, allowClose: true, allowLiquidation: true },
  { category: 6, categoryName: "stock", allowOpen: true, allowClose: true, allowLiquidation: true },
  { category: 7, categoryName: "etf", allowOpen: true, allowClose: true, allowLiquidation: true },
  { category: 8, categoryName: "other", allowOpen: true, allowClose: true, allowLiquidation: true },
];

describe("FxPauseBehaviorConfigPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.get.mockReset();
    adminApiMock.put.mockReset();
    hasPermissionMock.fn = () => true;
  });

  afterEach(() => cleanup());

  it("拉 8 行并渲染（每行类目编号可见）", async () => {
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    render(<FxPauseBehaviorConfigPage />);

    expect(await screen.findByText("FX 暂停类目行为配置")).toBeInTheDocument();
    // 8 行编辑按钮 → 8 行数据
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: /编\s*辑/ })).toHaveLength(8),
    );
  });

  it("GET 走 /admin/trading/fx-pause-behavior", async () => {
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    render(<FxPauseBehaviorConfigPage />);
    await waitFor(() => expect(adminApiMock.get).toHaveBeenCalled());
    expect(adminApiMock.get.mock.calls[0][0]).toBe("/admin/trading/fx-pause-behavior");
  });

  it("8 类目 label 映射正确（后端数字 categoryName 回退展示映射）", async () => {
    // categoryName 为纯数字时回退 FX_CATEGORY_LABELS。
    const numericName = EIGHT_ROWS.map((r) => ({ ...r, categoryName: String(r.category) }));
    adminApiMock.get.mockResolvedValue(numericName);
    render(<FxPauseBehaviorConfigPage />);

    expect(await screen.findByText("1 · 加密(crypto)")).toBeInTheDocument();
    expect(screen.getByText("2 · 外汇(forex)")).toBeInTheDocument();
    expect(screen.getByText("3 · 金属(metal)")).toBeInTheDocument();
    expect(screen.getByText("4 · 指数(index)")).toBeInTheDocument();
    expect(screen.getByText("5 · 能源(energy)")).toBeInTheDocument();
    expect(screen.getByText("6 · 股票(stock)")).toBeInTheDocument();
    expect(screen.getByText("7 · ETF")).toBeInTheDocument();
    expect(screen.getByText("8 · 其他(other)")).toBeInTheDocument();
  });

  it("有 fx:pause-behavior:edit 权限时显示编辑按钮", async () => {
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    render(<FxPauseBehaviorConfigPage />);
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: /编\s*辑/ })).toHaveLength(8),
    );
  });

  it("无 fx:pause-behavior:edit 权限时隐藏编辑按钮", async () => {
    hasPermissionMock.fn = () => false;
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    render(<FxPauseBehaviorConfigPage />);
    await waitFor(() => expect(adminApiMock.get).toHaveBeenCalled());
    // 后端 categoryName 非数字时优先展示后端值。
    expect(await screen.findByText("1 · crypto")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /编\s*辑/ })).not.toBeInTheDocument();
  });

  it("点编辑改开关 + 填 reason + 勾 acknowledge → PUT /{category} 携带 {allowOpen,allowClose,allowLiquidation,reason}", async () => {
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    adminApiMock.put.mockResolvedValue(undefined);
    render(<FxPauseBehaviorConfigPage />);
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: /编\s*辑/ })).toHaveLength(8),
    );

    // 编辑第 2 行（forex, category=2, allowOpen:false）
    fireEvent.click(screen.getAllByRole("button", { name: /编\s*辑/ })[1]);
    const dialog = await screen.findByRole("dialog");

    // 三个 Switch（顺序：开仓/平仓/强平）。把 allowOpen false→true。
    const switches = within(dialog).getAllByRole("switch");
    expect(switches).toHaveLength(3);
    fireEvent.click(switches[0]); // allowOpen false → true

    fireEvent.change(within(dialog).getByPlaceholderText("审计字段，必填"), {
      target: { value: "FX 恢复放开开仓" },
    });
    fireEvent.click(
      within(dialog).getByText("我已确认该改动会影响该类目在 FX 报价暂停期间全部用户的交易闸门"),
    );
    fireEvent.click(within(dialog).getByRole("button", { name: /确认更新/ }));

    await waitFor(() => expect(adminApiMock.put).toHaveBeenCalled());
    const [url, body] = adminApiMock.put.mock.calls[0];
    expect(url).toBe("/admin/trading/fx-pause-behavior/2");
    expect(body).toEqual({
      allowOpen: true,
      allowClose: true,
      allowLiquidation: false,
      reason: "FX 恢复放开开仓",
    });
  });

  it("acknowledge 未勾选时确认按钮禁用；reason 留空提交报校验、不发请求", async () => {
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    adminApiMock.put.mockResolvedValue(undefined);
    render(<FxPauseBehaviorConfigPage />);
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: /编\s*辑/ })).toHaveLength(8),
    );

    fireEvent.click(screen.getAllByRole("button", { name: /编\s*辑/ })[0]);
    const dialog = await screen.findByRole("dialog");

    const okBtn = within(dialog).getByRole("button", { name: /确认更新/ });
    expect(okBtn).toBeDisabled();

    fireEvent.click(
      within(dialog).getByText("我已确认该改动会影响该类目在 FX 报价暂停期间全部用户的交易闸门"),
    );
    expect(okBtn).not.toBeDisabled();
    fireEvent.click(okBtn);

    expect(await within(dialog).findByText("请填写原因（≤200）")).toBeInTheDocument();
    expect(adminApiMock.put).not.toHaveBeenCalled();
  });

  it("展示 allowClose 旁建议提示「手动平仓不限制，建议保持开启」", async () => {
    adminApiMock.get.mockResolvedValue(EIGHT_ROWS);
    render(<FxPauseBehaviorConfigPage />);
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: /编\s*辑/ })).toHaveLength(8),
    );
    fireEvent.click(screen.getAllByRole("button", { name: /编\s*辑/ })[0]);
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("手动平仓不限制，建议保持开启")).toBeInTheDocument();
  });
});
