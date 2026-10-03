import { afterEach, describe, expect, it, vi, beforeEach } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { WithdrawDetailPage } from "./WithdrawDetailPage";
import { useAdminAuthStore } from "../../lib/auth/adminAuthStore";

const PENDING_ITEM = {
  withdrawId: "900000001",
  userId: "48275236470263808",
  amount: "100.00000000",
  currency: "USDT",
  network: "ERC20" as const,
  targetAddress: "0xB010093f3e20334962023D9cc26D51c1E7F3BB51",
  status: "PENDING" as const,
  coolingUntil: null,
  delayedUntil: null,
  rejectReason: null,
  txHash: null,
  confirmations: null,
  failureReason: null,
  createdAt: "2026-05-15T10:30:00+08:00",
};

// vi.mock 会被 hoisted 到文件顶部，工厂函数体里不能直接引用 PENDING_ITEM；
// 用 vi.hoisted 显式提升一个共享 mock 对象，再在工厂内通过引用透传。
const apiMock = vi.hoisted(() => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: apiMock.adminApi,
  ApiError: class ApiError extends Error {
    code: string;
    httpStatus: number;
    constructor(code: string, httpStatus: number, message: string) {
      super(message);
      this.code = code;
      this.httpStatus = httpStatus;
    }
  },
}));

function renderWithRoute(path = "/admin/withdraws/900000001") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/withdraws/:id" element={<WithdrawDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("WithdrawDetailPage (TC-WD-FE-310 ~ 315)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMock.adminApi.get.mockResolvedValue(PENDING_ITEM);
    apiMock.adminApi.post.mockReset();
    // 默认 SUPER_ADMIN 全权限
    useAdminAuthStore.setState({ permissions: [], isSuperAdmin: true });
  });

  afterEach(() => {
    cleanup();
  });

  it("TC-WD-FE-310: 渲染基本信息 + 状态 alert + 3 个 action 按钮", async () => {
    renderWithRoute();
    expect(await screen.findByText(/#900000001/)).toBeInTheDocument();
    // 状态 alert
    expect(screen.getByText(/⏰ 待审核/)).toBeInTheDocument();
    // 基本信息（§4 commit B：基本信息卡 + 用户信息卡都显示 userId，所以 getAll）
    expect(screen.getAllByText("48275236470263808").length).toBeGreaterThan(0);
    expect(screen.getByText(/100\.00000000 USDT/)).toBeInTheDocument();
    // 按钮：approve + reject（PENDING 时启用），emergency-cancel 不显示（仅 APPROVED_DELAYED 显示）
    expect(screen.getByText(/✅ 通过出金/)).toBeInTheDocument();
    expect(screen.getByText(/❌ 拒绝出金/)).toBeInTheDocument();
    expect(screen.queryByText(/⚠️ 紧急取消/)).not.toBeInTheDocument();
  });

  it("TC-WD-FE-314: 无 withdraw:review 权限时 approve/reject disabled", async () => {
    useAdminAuthStore.setState({
      permissions: ["withdraw:view"], // 只有 view 没 review
      isSuperAdmin: false,
    });
    renderWithRoute();
    await waitFor(() => {
      const approveBtn = screen.getByText(/✅ 通过出金/).closest("button");
      const rejectBtn = screen.getByText(/❌ 拒绝出金/).closest("button");
      expect(approveBtn).toBeDisabled();
      expect(rejectBtn).toBeDisabled();
    });
  });

  it("TC-WD-FE-313: APPROVED_DELAYED 状态显示 emergency-cancel 按钮", async () => {
    apiMock.adminApi.get.mockResolvedValueOnce({
      ...PENDING_ITEM,
      status: "APPROVED_DELAYED",
      delayedUntil: "2026-05-15T16:30:00+08:00",
    });
    renderWithRoute();
    await waitFor(() => {
      expect(screen.getByText(/⚠️ 紧急取消/)).toBeInTheDocument();
    });
    // PENDING-only 操作此时 disabled
    const approveBtn = screen.getByText(/✅ 通过出金/).closest("button");
    expect(approveBtn).toBeDisabled();
  });

  it("TC-WD-FE-315: 90500 错误码渲染 404 result", async () => {
    apiMock.adminApi.get.mockRejectedValueOnce({ code: "90500", message: "Not Found" });
    renderWithRoute();
    await waitFor(() => {
      expect(screen.getByText("出金单不存在")).toBeInTheDocument();
    });
  });

  it("TC-WD-FE-316 (§4 commit B): kycLevel=1 显示 ✅ 已认证", async () => {
    apiMock.adminApi.get.mockResolvedValueOnce({ ...PENDING_ITEM, kycLevel: 1 });
    renderWithRoute();
    await waitFor(() => {
      expect(screen.getByText(/✅ 1（已认证）/)).toBeInTheDocument();
    });
  });

  it("TC-WD-FE-317 (§4 commit C): kycLevel=null 不出现 ✅/⚠️ 徽章（用 — 占位）", async () => {
    apiMock.adminApi.get.mockResolvedValueOnce({ ...PENDING_ITEM, kycLevel: null });
    renderWithRoute();
    await waitFor(() => {
      // 详情页加载完成：标题 + ID
      expect(screen.getByText(/出金单/)).toBeInTheDocument();
    });
    // 用户信息卡片渲染但不出现 KYC 徽章
    expect(screen.queryByText(/✅ 1（已认证）/)).not.toBeInTheDocument();
    expect(screen.queryByText(/⚠️ 0（未认证）/)).not.toBeInTheDocument();
  });

  it("TC-WD-FE-318 (§4 commit C): userEmail + dailyAccumulatedUsd enriched", async () => {
    apiMock.adminApi.get.mockResolvedValueOnce({
      ...PENDING_ITEM,
      kycLevel: 1,
      userEmail: "alice@example.com",
      dailyAccumulatedUsd: "12500",
    });
    renderWithRoute();
    await waitFor(() => {
      expect(screen.getByText("alice@example.com")).toBeInTheDocument();
    });
    // 统一精度：USDT 金额千分位 + 上限同样按 2 位金额展示
    expect(screen.getByText(/12,500.00 \/ 30,000.00 USDT/)).toBeInTheDocument();
  });

  it("TC-WD-FE-319 (§4 commit C): dailyAccumulatedUsd ≥24K 触发橙色高亮", async () => {
    apiMock.adminApi.get.mockResolvedValueOnce({
      ...PENDING_ITEM,
      kycLevel: 1,
      userEmail: "bob@example.com",
      dailyAccumulatedUsd: "25000",
    });
    renderWithRoute();
    const node = await waitFor(() => screen.getByText(/25,000.00 \/ 30,000.00 USDT/));
    // span 行内样式带橙色（jsdom 把 #fa8c16 解析成 rgb(250, 140, 22)）
    const style = (node as HTMLElement).getAttribute("style") ?? "";
    expect(style).toMatch(/rgb\(250,\s*140,\s*22\)|fa8c16/i);
  });
});
