import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MarginModeConfigPage } from "./MarginModeConfigPage";

// adminApi mock（透传层），由各用例按需设定返回。
const adminApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock("../../lib/api/apiClient", () => ({ adminApi: adminApiMock }));

// 权限 mock：RequiresPermission → useHasPermission。默认有 margin-mode-config:edit；按用例覆盖。
const hasPermissionMock = vi.hoisted(() => ({ fn: (): boolean => true }));
vi.mock("../../lib/auth/usePermission", () => ({
  useHasPermission: () => hasPermissionMock.fn,
}));

describe("MarginModeConfigPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.get.mockReset();
    adminApiMock.put.mockReset();
    hasPermissionMock.fn = () => true;
  });

  afterEach(() => cleanup());

  it("渲染标题 + 拉取并展示当前冷静期值", async () => {
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    render(<MarginModeConfigPage />);

    expect(await screen.findByText("保证金模式冷静期配置")).toBeInTheDocument();
    expect(await screen.findByText("300 秒")).toBeInTheDocument();
  });

  it("GET 走 /admin/trading/margin-mode-config", async () => {
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    render(<MarginModeConfigPage />);
    await waitFor(() => expect(adminApiMock.get).toHaveBeenCalled());
    expect(adminApiMock.get.mock.calls[0][0]).toBe("/admin/trading/margin-mode-config");
  });

  it("有 margin-mode-config:edit 权限时显示保存按钮", async () => {
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    render(<MarginModeConfigPage />);
    await waitFor(() => expect(screen.getByText("300 秒")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /保\s*存/ })).toBeInTheDocument();
  });

  it("无 margin-mode-config:edit 权限时隐藏保存按钮", async () => {
    hasPermissionMock.fn = () => false;
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    render(<MarginModeConfigPage />);
    await waitFor(() => expect(screen.getByText("300 秒")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /保\s*存/ })).not.toBeInTheDocument();
  });

  it("点保存 + 填 reason + 勾 acknowledge → PUT 携带 {coolingPeriodSeconds, reason}", async () => {
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    adminApiMock.put.mockResolvedValue(undefined);
    render(<MarginModeConfigPage />);
    await waitFor(() => expect(screen.getByText("300 秒")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /保\s*存/ }));

    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByPlaceholderText("审计字段，必填"), {
      target: { value: "调长冷静期" },
    });
    fireEvent.click(within(dialog).getByText("我已确认该改动会影响全部用户的保证金模式切换"));
    fireEvent.click(within(dialog).getByRole("button", { name: /确认更新/ }));

    await waitFor(() => expect(adminApiMock.put).toHaveBeenCalled());
    const [url, body] = adminApiMock.put.mock.calls[0];
    expect(url).toBe("/admin/trading/margin-mode-config");
    expect(body).toEqual({ coolingPeriodSeconds: 300, reason: "调长冷静期" });
  });

  it("acknowledge 未勾选时确认更新按钮禁用；reason 留空提交报校验、不发请求", async () => {
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    adminApiMock.put.mockResolvedValue(undefined);
    render(<MarginModeConfigPage />);
    await waitFor(() => expect(screen.getByText("300 秒")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /保\s*存/ }));
    const dialog = await screen.findByRole("dialog");

    const okBtn = within(dialog).getByRole("button", { name: /确认更新/ });
    expect(okBtn).toBeDisabled();

    // 勾选 acknowledge 后启用，但 reason 留空 → 校验拦截、不发请求
    fireEvent.click(within(dialog).getByText("我已确认该改动会影响全部用户的保证金模式切换"));
    expect(okBtn).not.toBeDisabled();
    fireEvent.click(okBtn);

    expect(await within(dialog).findByText("请填写原因（≤200）")).toBeInTheDocument();
    expect(adminApiMock.put).not.toHaveBeenCalled();
  });

  it("范围越界（<60）被 InputNumber 钳制到边界，不会以越界值开确认 Modal", async () => {
    adminApiMock.get.mockResolvedValue({ coolingPeriodSeconds: 300 });
    render(<MarginModeConfigPage />);
    await waitFor(() => expect(screen.getByText("300 秒")).toBeInTheDocument());

    // InputNumber min=60：键入 30 失焦后被钳制到 60（前端范围守卫），不会以 30 提交。
    const input = screen.getByRole("spinbutton") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "30" } });
    fireEvent.blur(input);
    await waitFor(() => expect(input.value).toBe("60"));

    fireEvent.click(screen.getByRole("button", { name: /保\s*存/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByPlaceholderText("审计字段，必填"), {
      target: { value: "边界值" },
    });
    fireEvent.click(within(dialog).getByText("我已确认该改动会影响全部用户的保证金模式切换"));
    fireEvent.click(within(dialog).getByRole("button", { name: /确认更新/ }));

    await waitFor(() => expect(adminApiMock.put).toHaveBeenCalled());
    expect(adminApiMock.put.mock.calls[0][1]).toEqual({ coolingPeriodSeconds: 60, reason: "边界值" });
  });
});
