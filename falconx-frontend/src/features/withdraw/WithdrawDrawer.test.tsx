import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FalconApiError } from "../../lib/api";
import { useAuthStore } from "../auth/authStore";
import * as tradingApi from "../trading/tradingApi";
import * as withdrawApi from "./withdrawApi";
import { WithdrawDrawer } from "./WithdrawDrawer";
import { describeWithdrawError } from "./types";

/**
 * STAGE-7-CLIENT WithdrawDrawer Vitest 套件。
 *
 * 覆盖：
 *  - TC-WD-FE-001 错误码 → 友好文案纯函数
 *  - TC-WD-FE-002 无 ACTIVE 白名单时禁用提交并提示去添加
 *  - TC-WD-FE-003 ACTIVE 白名单存在时表单可提交，成功后展示订单短号
 *  - TC-WD-FE-004 30043 KYC_REQUIRED 提示带「去 KYC」回跳按钮
 */

function renderDrawer(onOpenKyc?: () => void) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <WithdrawDrawer open={true} onClose={() => undefined} onOpenKyc={onOpenKyc} />
    </QueryClientProvider>,
  );
}

function setAuthSession() {
  act(() => {
    useAuthStore.getState().setSession({
      accessToken: "test-token",
      refreshToken: "test-refresh",
      accessTokenExpiresAt: Date.now() + 60_000,
      refreshTokenExpiresAt: Date.now() + 3_600_000,
      userStatus: "ACTIVE",
      emailVerified: true,
    });
  });
}

beforeEach(() => {
  setAuthSession();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  act(() => useAuthStore.getState().clearSession());
});

describe("describeWithdrawError", () => {
  it("TC-WD-FE-001 maps known codes to Chinese hints with action hints", () => {
    expect(describeWithdrawError("30043", "fallback")).toEqual({
      text: "出金前需完成 KYC 实名认证",
      actionHint: "openKyc",
    });
    expect(describeWithdrawError("30056", "fallback").actionHint).toBe("openKyc");
    expect(describeWithdrawError("30046", "fallback").actionHint).toBe("addWhitelist");
    expect(describeWithdrawError("30055", "fallback").text).toMatch(/24 小时冷静期/);
    expect(describeWithdrawError("99999", "兜底文案")).toEqual({ text: "兜底文案" });
  });
});

describe("WithdrawDrawer", () => {
  it("TC-WD-FE-002 disables submit when no ACTIVE whitelist exists", async () => {
    vi.spyOn(tradingApi, "getAccount").mockResolvedValue({
      accountId: 1,
      userId: 1,
      currency: "USDT",
      balance: "1000",
      frozen: "0",
      marginUsed: "0",
      available: "1000",
      marginMode: "ISOLATED",
    });
    vi.spyOn(withdrawApi, "listWhitelists").mockResolvedValue([]);
    renderDrawer();

    await waitFor(() => expect(screen.getByText("钱包出金")).toBeInTheDocument());
    expect(screen.getByText("USDT · ERC20 / TRC20")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText(/尚无已生效的白名单/)).toBeInTheDocument());
    const submitBtn = screen.getByRole("button", { name: "提交出金" }) as HTMLButtonElement;
    expect(submitBtn.disabled).toBe(true);
  });

  it("TC-WD-FE-003 submits with selected ACTIVE whitelist and shows last-8 id", async () => {
    vi.spyOn(tradingApi, "getAccount").mockResolvedValue({
      accountId: 1,
      userId: 1,
      currency: "USDT",
      balance: "1000",
      frozen: "0",
      marginUsed: "0",
      available: "1000",
      marginMode: "ISOLATED",
    });
    vi.spyOn(withdrawApi, "listWhitelists").mockResolvedValue([
      {
        id: "9001",
        userId: "1",
        network: "ERC20",
        address: "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa",
        label: "主钱包",
        status: "ACTIVE",
        activatedAt: "2026-05-18T00:00:00Z",
        createdAt: "2026-05-17T00:00:00Z",
      },
    ]);
    const submitSpy = vi.spyOn(withdrawApi, "submitWithdraw").mockResolvedValue({
      withdrawId: "1234567890123456789",
      userId: "1",
      amount: "100",
      currency: "USDT",
      network: "ERC20",
      targetAddress: "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa",
      status: "COOLING",
      coolingUntil: "2026-05-19T02:00:00Z",
      delayedUntil: null,
      rejectReason: null,
      txHash: null,
      confirmations: 0,
      failureReason: null,
      createdAt: "2026-05-19T00:00:00Z",
    });

    renderDrawer();
    await waitFor(() => expect(screen.getByText(/主钱包/)).toBeInTheDocument());

    const amountInput = screen.getByPlaceholderText("例如 100");
    await userEvent.type(amountInput, "100");
    await userEvent.click(screen.getByRole("button", { name: "提交出金" }));

    await waitFor(() => {
      expect(submitSpy).toHaveBeenCalledTimes(1);
    });
    await waitFor(() => {
      expect(screen.getByText(/已提交，进入冷静期/)).toBeInTheDocument();
    });
    // 短号取后 8 位
    expect(screen.getByText(/56789/)).toBeInTheDocument();
  });

  it("TC-WD-FE-004 surfaces 30043 hint with 去 KYC button that calls onOpenKyc", async () => {
    vi.spyOn(tradingApi, "getAccount").mockResolvedValue({
      accountId: 1,
      userId: 1,
      currency: "USDT",
      balance: "1000",
      frozen: "0",
      marginUsed: "0",
      available: "1000",
      marginMode: "ISOLATED",
    });
    vi.spyOn(withdrawApi, "listWhitelists").mockResolvedValue([
      {
        id: "9001",
        userId: "1",
        network: "ERC20",
        address: "0xAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaaAaaaaaaa",
        label: "主钱包",
        status: "ACTIVE",
        activatedAt: "2026-05-18T00:00:00Z",
        createdAt: "2026-05-17T00:00:00Z",
      },
    ]);
    vi.spyOn(withdrawApi, "submitWithdraw").mockRejectedValue(
      new FalconApiError("30043", "Withdraw KYC Required", "trace-1", 400, null),
    );
    const onOpenKyc = vi.fn();

    renderDrawer(onOpenKyc);
    await waitFor(() => expect(screen.getByText(/主钱包/)).toBeInTheDocument());
    await userEvent.type(screen.getByPlaceholderText("例如 100"), "100");
    await userEvent.click(screen.getByRole("button", { name: "提交出金" }));

    await waitFor(() =>
      expect(screen.getByText(/出金前需完成 KYC 实名认证/)).toBeInTheDocument(),
    );
    await userEvent.click(screen.getByRole("button", { name: "去 KYC" }));
    expect(onOpenKyc).toHaveBeenCalled();
  });
});
