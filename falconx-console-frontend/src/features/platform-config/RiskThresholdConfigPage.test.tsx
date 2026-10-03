import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { RiskThresholdConfigPage } from "./RiskThresholdConfigPage";

// adminApi mock（透传层），由各用例按需设定返回。
const adminApiMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}));
vi.mock("../../lib/api/apiClient", () => ({ adminApi: adminApiMock }));

// 权限 mock：RequiresPermission → useHasPermission。默认有 risk-threshold:edit；按用例覆盖。
const hasPermissionMock = vi.hoisted(() => ({ fn: (): boolean => true }));
vi.mock("../../lib/auth/usePermission", () => ({
  useHasPermission: () => hasPermissionMock.fn,
}));

const ACK_LABEL = "我已确认该改动会影响全部用户的强平 / 追保触发";

describe("RiskThresholdConfigPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    adminApiMock.get.mockReset();
    adminApiMock.put.mockReset();
    hasPermissionMock.fn = () => true;
  });

  afterEach(() => cleanup());

  it("渲染标题 + 拉取并展示当前阈值（含百分比）", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    render(<RiskThresholdConfigPage />);

    expect(await screen.findByText("强平 / 追保阈值配置")).toBeInTheDocument();
    expect(await screen.findByText("0.30（30%）")).toBeInTheDocument();
    expect(await screen.findByText("1.00（100%）")).toBeInTheDocument();
  });

  it("GET 走 /admin/trading/risk-thresholds", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    render(<RiskThresholdConfigPage />);
    await waitFor(() => expect(adminApiMock.get).toHaveBeenCalled());
    expect(adminApiMock.get.mock.calls[0][0]).toBe("/admin/trading/risk-thresholds");
  });

  it("GET 返回 number 也兼容（String 转）", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: 0.3, marginCallLevel: 1 });
    render(<RiskThresholdConfigPage />);
    expect(await screen.findByText("0.3（30%）")).toBeInTheDocument();
    expect(await screen.findByText("1（100%）")).toBeInTheDocument();
  });

  it("有 risk-threshold:edit 权限时显示保存按钮", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    render(<RiskThresholdConfigPage />);
    await waitFor(() => expect(screen.getByText("0.30（30%）")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /保\s*存/ })).toBeInTheDocument();
  });

  it("无 risk-threshold:edit 权限时隐藏保存按钮", async () => {
    hasPermissionMock.fn = () => false;
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    render(<RiskThresholdConfigPage />);
    await waitFor(() => expect(screen.getByText("0.30（30%）")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /保\s*存/ })).not.toBeInTheDocument();
  });

  it("点保存 + 填 reason + 勾 acknowledge → PUT 携带 {stopOutLevel, marginCallLevel, reason}", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    adminApiMock.put.mockResolvedValue(undefined);
    render(<RiskThresholdConfigPage />);
    await waitFor(() => expect(screen.getByText("0.30（30%）")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /保\s*存/ }));

    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByPlaceholderText("审计字段，必填"), {
      target: { value: "收紧强平阈值" },
    });
    fireEvent.click(within(dialog).getByText(ACK_LABEL));
    fireEvent.click(within(dialog).getByRole("button", { name: /确认更新/ }));

    await waitFor(() => expect(adminApiMock.put).toHaveBeenCalled());
    const [url, body] = adminApiMock.put.mock.calls[0];
    expect(url).toBe("/admin/trading/risk-thresholds");
    expect(body).toEqual({ stopOutLevel: "0.3", marginCallLevel: "1", reason: "收紧强平阈值" });
  });

  it("acknowledge 未勾选时确认更新按钮禁用；reason 留空提交报校验、不发请求", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    adminApiMock.put.mockResolvedValue(undefined);
    render(<RiskThresholdConfigPage />);
    await waitFor(() => expect(screen.getByText("0.30（30%）")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: /保\s*存/ }));
    const dialog = await screen.findByRole("dialog");

    const okBtn = within(dialog).getByRole("button", { name: /确认更新/ });
    expect(okBtn).toBeDisabled();

    // 勾选 acknowledge 后启用，但 reason 留空 → 校验拦截、不发请求
    fireEvent.click(within(dialog).getByText(ACK_LABEL));
    expect(okBtn).not.toBeDisabled();
    fireEvent.click(okBtn);

    expect(await within(dialog).findByText("请填写原因（≤200）")).toBeInTheDocument();
    expect(adminApiMock.put).not.toHaveBeenCalled();
  });

  it("stopOut 越界（<0.05）被 InputNumber 钳制到边界，不会以越界值提交", async () => {
    adminApiMock.get.mockResolvedValue({ stopOutLevel: "0.30", marginCallLevel: "1.00" });
    adminApiMock.put.mockResolvedValue(undefined);
    render(<RiskThresholdConfigPage />);
    await waitFor(() => expect(screen.getByText("0.30（30%）")).toBeInTheDocument());

    // 第一个 spinbutton 为 stopOutLevel，min=0.05：键入 0.01 失焦后钳制到 0.05。
    const inputs = screen.getAllByRole("spinbutton") as HTMLInputElement[];
    const stopOut = inputs[0];
    fireEvent.change(stopOut, { target: { value: "0.01" } });
    fireEvent.blur(stopOut);
    await waitFor(() => expect(stopOut.value).toBe("0.05"));

    fireEvent.click(screen.getByRole("button", { name: /保\s*存/ }));
    const dialog = await screen.findByRole("dialog");
    fireEvent.change(within(dialog).getByPlaceholderText("审计字段，必填"), {
      target: { value: "边界值" },
    });
    fireEvent.click(within(dialog).getByText(ACK_LABEL));
    fireEvent.click(within(dialog).getByRole("button", { name: /确认更新/ }));

    await waitFor(() => expect(adminApiMock.put).toHaveBeenCalled());
    expect(adminApiMock.put.mock.calls[0][1]).toEqual({
      stopOutLevel: "0.05",
      marginCallLevel: "1",
      reason: "边界值",
    });
  });
});
