import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { TierConfigListPage } from "./TierConfigListPage";
import { TierFormModal } from "./TierFormModal";

// adminApi mock（透传层），由各用例按需设定返回。
const adminApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock("../../lib/api/apiClient", () => ({ adminApi: adminApiMock }));

// 权限 mock：RequiresPermission → useHasPermission。默认有 tier:edit；按用例覆盖。
const hasPermissionMock = vi.hoisted(() => ({ fn: (): boolean => true }));
vi.mock("../../lib/auth/usePermission", () => ({
  useHasPermission: () => hasPermissionMock.fn,
}));

const SAMPLE_TIERS = [
  {
    id: "7001",
    symbol: "BTCUSDT",
    groupCode: "default",
    tierNo: 1,
    notionalLower: "0",
    notionalUpper: "100000",
    maxLeverage: 100,
    mmRate: "0.005",
    enabled: true,
  },
  {
    id: "7002",
    symbol: "BTCUSDT",
    groupCode: "default",
    tierNo: 2,
    notionalLower: "100000",
    notionalUpper: null,
    maxLeverage: 50,
    mmRate: "0.01",
    enabled: true,
  },
  {
    id: "7003",
    symbol: "ETHUSDT",
    groupCode: "default",
    tierNo: 1,
    notionalLower: "0",
    notionalUpper: null,
    maxLeverage: 75,
    mmRate: "0.0066",
    enabled: false,
  },
];

function mockListWithSamples() {
  adminApiMock.get.mockResolvedValue({ items: SAMPLE_TIERS, total: 3, page: 1, size: 20 });
}

describe("TierConfigListPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.get.mockReset();
    adminApiMock.post.mockReset();
    adminApiMock.put.mockReset();
    adminApiMock.delete.mockReset();
    hasPermissionMock.fn = () => true;
  });

  afterEach(() => cleanup());

  it("渲染标题 + 列表按 symbol 分组 + 展开行显示各档位", async () => {
    mockListWithSamples();
    render(<TierConfigListPage />);

    expect(await screen.findByText("杠杆档位配置")).toBeInTheDocument();

    await waitFor(() => {
      // 两个分组父行（BTCUSDT / ETHUSDT）
      expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
      expect(screen.getByText("ETHUSDT")).toBeInTheDocument();
    });
    // 展开行（defaultExpandAllRows）显示档位区间：无上限渲染 ∞
    expect(screen.getByText("0 ~ 100000")).toBeInTheDocument();
    expect(screen.getByText("100000 ~ ∞")).toBeInTheDocument();
    // 停用档位 Tag
    expect(screen.getByText("已停用")).toBeInTheDocument();
  });

  it("查询走 /admin/trading/tiers + 默认分页参数", async () => {
    mockListWithSamples();
    render(<TierConfigListPage />);
    await waitFor(() => expect(adminApiMock.get).toHaveBeenCalled());
    const url = adminApiMock.get.mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/trading\/tiers\?/);
    expect(url).toContain("page=1");
    expect(url).toContain("size=20");
  });

  it("有 tier:edit 权限时显示新建/编辑/删除按钮", async () => {
    mockListWithSamples();
    render(<TierConfigListPage />);
    await waitFor(() => expect(screen.getByText("BTCUSDT")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /新建档位/ })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /编\s*辑/ }).length).toBeGreaterThan(0);
  });

  it("无 tier:edit 权限时隐藏新建/编辑/删除按钮", async () => {
    hasPermissionMock.fn = () => false;
    mockListWithSamples();
    render(<TierConfigListPage />);
    await waitFor(() => expect(screen.getByText("BTCUSDT")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /新建档位/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /编\s*辑/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /删\s*除/ })).not.toBeInTheDocument();
  });

  it("list 失败时不渲染数据", async () => {
    adminApiMock.get.mockRejectedValue({ code: "99999", message: "Internal Error" });
    render(<TierConfigListPage />);
    expect(await screen.findByText("杠杆档位配置")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByText("BTCUSDT")).not.toBeInTheDocument();
    });
  });
});

describe("TierFormModal（新建/校验/reason 二次确认）", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.post.mockReset();
  });
  afterEach(() => cleanup());

  function fillNumber(label: RegExp, value: string) {
    const input = screen.getByLabelText(label) as HTMLInputElement;
    fireEvent.change(input, { target: { value } });
  }

  it("acknowledge 未勾选时创建按钮禁用，勾选后启用", async () => {
    render(
      <TierFormModal open mode="create" existing={null} onClose={() => {}} onSuccess={() => {}} />,
    );
    const okBtn = screen.getByRole("button", { name: /创\s*建/ });
    expect(okBtn).toBeDisabled();
    fireEvent.click(screen.getByText("我已确认该改动会影响开仓杠杆与维持保证金"));
    expect(okBtn).not.toBeDisabled();
  });

  it("maxLeverage × mmRate > 1.0 时提示校验失败、不发请求", async () => {
    adminApiMock.post.mockResolvedValue({});
    render(
      <TierFormModal open mode="create" existing={null} onClose={() => {}} onSuccess={() => {}} />,
    );
    fireEvent.change(screen.getByLabelText("Symbol"), { target: { value: "BTCUSDT" } });
    fillNumber(/档位序号/, "1");
    fillNumber(/名义价值下界/, "0");
    fillNumber(/最大杠杆/, "100");
    fillNumber(/维持保证金率/, "0.05"); // 100 × 0.05 = 5.0 > 1.0
    fireEvent.change(screen.getByLabelText(/原因/), { target: { value: "test" } });
    fireEvent.click(screen.getByText("我已确认该改动会影响开仓杠杆与维持保证金"));
    fireEvent.click(screen.getByRole("button", { name: /创\s*建/ }));

    expect(await screen.findByText(/maxLeverage × mmRate 必须 ≤ 1.0/)).toBeInTheDocument();
    expect(adminApiMock.post).not.toHaveBeenCalled();
  });

  it("reason 必填：留空提交报校验、不发请求", async () => {
    adminApiMock.post.mockResolvedValue({});
    render(
      <TierFormModal open mode="create" existing={null} onClose={() => {}} onSuccess={() => {}} />,
    );
    fireEvent.change(screen.getByLabelText("Symbol"), { target: { value: "BTCUSDT" } });
    fillNumber(/档位序号/, "1");
    fillNumber(/名义价值下界/, "0");
    fillNumber(/最大杠杆/, "100");
    fillNumber(/维持保证金率/, "0.005");
    fireEvent.click(screen.getByText("我已确认该改动会影响开仓杠杆与维持保证金"));
    fireEvent.click(screen.getByRole("button", { name: /创\s*建/ }));

    expect(await screen.findByText("请填写原因（≤200）")).toBeInTheDocument();
    expect(adminApiMock.post).not.toHaveBeenCalled();
  });

  it("校验通过 + reason + acknowledge → POST /admin/trading/tiers 携带字段", async () => {
    adminApiMock.post.mockResolvedValue({});
    const onSuccess = vi.fn();
    render(
      <TierFormModal open mode="create" existing={null} onClose={() => {}} onSuccess={onSuccess} />,
    );
    fireEvent.change(screen.getByLabelText("Symbol"), { target: { value: "BTCUSDT" } });
    fillNumber(/档位序号/, "1");
    fillNumber(/名义价值下界/, "0");
    fillNumber(/名义价值上界/, "100000");
    fillNumber(/最大杠杆/, "100");
    fillNumber(/维持保证金率/, "0.005");
    fireEvent.change(screen.getByLabelText(/原因/), { target: { value: "初始化档位" } });
    fireEvent.click(screen.getByText("我已确认该改动会影响开仓杠杆与维持保证金"));
    fireEvent.click(screen.getByRole("button", { name: /创\s*建/ }));

    await waitFor(() => expect(adminApiMock.post).toHaveBeenCalled());
    const [url, body] = adminApiMock.post.mock.calls[0];
    expect(url).toBe("/admin/trading/tiers");
    expect(body).toMatchObject({
      symbol: "BTCUSDT",
      groupCode: "default",
      tierNo: 1,
      notionalLower: "0",
      notionalUpper: "100000",
      maxLeverage: 100,
      mmRate: "0.005",
      reason: "初始化档位",
    });
  });
});

// 删除 / 编辑按钮位于展开行内（非 fixed 列），可在 jsdom 触达；走 List 页交互一条覆盖。
describe("TierConfigListPage 删除流程", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.get.mockReset();
    adminApiMock.delete.mockReset();
    hasPermissionMock.fn = () => true;
  });
  afterEach(() => cleanup());

  it("点击删除 → 弹软删 Modal → reason + acknowledge → DELETE 携带 reason", async () => {
    mockListWithSamples();
    adminApiMock.delete.mockResolvedValue(undefined);
    render(<TierConfigListPage />);
    await waitFor(() => expect(screen.getByText("BTCUSDT")).toBeInTheDocument());

    // 第一行（tierNo=1, enabled）的删除按钮
    const deleteBtns = screen.getAllByRole("button", { name: /删\s*除/ });
    fireEvent.click(deleteBtns[0]);

    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByPlaceholderText("删除原因（必填）"), {
      target: { value: "下线档位" },
    });
    fireEvent.click(within(dialog).getByText("我已确认软删该档位"));
    fireEvent.click(within(dialog).getByRole("button", { name: /确认删除/ }));

    await waitFor(() => expect(adminApiMock.delete).toHaveBeenCalled());
    const [url, body] = adminApiMock.delete.mock.calls[0];
    expect(url).toBe("/admin/trading/tiers/7001");
    expect(body).toEqual({ reason: "下线档位" });
  });
});
