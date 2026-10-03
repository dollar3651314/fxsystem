import { describe, expect, it, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { WithdrawListPage } from "./WithdrawListPage";

// 捕获最近一次传给 useAdminTradingSocket 的 handlers，测试可主动触发 onWithdrawStatusChanged
let lastHandlers: { onWithdrawStatusChanged?: (e: unknown) => void } = {};
vi.mock("../trading/useAdminTradingSocket", () => ({
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

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn().mockResolvedValue({
      page: 1,
      pageSize: 20,
      total: 2,
      items: [
        {
          withdrawId: "900000001",
          userId: "48275236470263808",
          amount: "100.00000000",
          currency: "USDT",
          network: "ERC20",
          targetAddress: "0xB010093f3e20334962023D9cc26D51c1E7F3BB51",
          status: "PENDING",
          coolingUntil: null,
          delayedUntil: null,
          rejectReason: null,
          txHash: null,
          confirmations: null,
          failureReason: null,
          createdAt: "2026-05-15T10:30:00+08:00",
        },
        {
          withdrawId: "900000002",
          userId: "48275236470263808",
          amount: "5000.00000000",
          currency: "USDT",
          network: "ERC20",
          targetAddress: "0xB010093f3e20334962023D9cc26D51c1E7F3BB51",
          status: "APPROVED_DELAYED",
          coolingUntil: null,
          delayedUntil: "2026-05-15T16:30:00+08:00",
          rejectReason: null,
          txHash: null,
          confirmations: null,
          failureReason: null,
          createdAt: "2026-05-15T10:30:00+08:00",
        },
      ],
    }),
    post: vi.fn(),
  },
}));

describe("WithdrawListPage (TC-WD-FE-300 ~ 303)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("TC-WD-FE-300: 渲染标题 + 待办分组卡片 + 列表条目", async () => {
    render(
      <MemoryRouter>
        <WithdrawListPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("出金审核")).toBeInTheDocument();

    // 待办分组卡片
    expect(screen.getByText(/⏰ PENDING 待审核/)).toBeInTheDocument();
    expect(screen.getByText(/⏱ APPROVED_DELAYED 延迟期/)).toBeInTheDocument();

    // 列表数据加载后渲染
    await waitFor(() => {
      expect(screen.getByText("900000001")).toBeInTheDocument();
      expect(screen.getByText("900000002")).toBeInTheDocument();
    });
  });

  it("TC-WD-FE-301: PENDING 待办计数 = 1 + 合计 = $100", async () => {
    render(
      <MemoryRouter>
        <WithdrawListPage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      // PENDING 1 笔合计 100
      expect(screen.getByText(/笔 \/ 合计 \$100\.00/)).toBeInTheDocument();
      // APPROVED_DELAYED 1 笔合计 5000（统一精度：千分位）
      expect(screen.getByText(/笔 \/ 合计 \$5,000\.00/)).toBeInTheDocument();
    });
  });

  it("TC-WD-FE-302 (§4 commit D): admin.withdraw.status-changed 触发 refetch + 行 flash", async () => {
    const { adminApi } = await import("../../lib/api/apiClient");
    render(
      <MemoryRouter>
        <WithdrawListPage />
      </MemoryRouter>,
    );

    // 首次加载一次
    await waitFor(() => expect(adminApi.get).toHaveBeenCalledTimes(1));

    // WS 推送：模拟 trading-core 把 900000001 切到 APPROVED
    act(() => {
      lastHandlers.onWithdrawStatusChanged?.({
        withdrawId: "900000001",
        userId: "48275236470263808",
        status: "APPROVED",
        txHash: null,
        confirmations: null,
        failureReason: null,
        occurredAt: "2026-05-19T04:00:00Z",
      });
    });

    // 第二次拉列表（refetch）
    await waitFor(() => expect(adminApi.get).toHaveBeenCalledTimes(2));

    // 行高亮 class 落到 tr
    await waitFor(() => {
      const row = document.querySelector("tr.fx-row-flash");
      expect(row).not.toBeNull();
    });
  });

  it("TC-WD-FE-303 (§4 commit D): socket open 时标题旁显示「实时」徽章", async () => {
    render(
      <MemoryRouter>
        <WithdrawListPage />
      </MemoryRouter>,
    );
    // AntD Badge 把 text 渲染为 <span class="ant-badge-status-text">实时</span>，
    // 跟标题中可能存在的 "实时" 字符冲突时用更宽松的匹配避免边界 case
    await waitFor(() => {
      const node = document.querySelector(".ant-badge-status-text");
      expect(node?.textContent).toBe("实时");
    });
  });
});
