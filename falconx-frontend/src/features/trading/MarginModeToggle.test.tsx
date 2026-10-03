import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FalconApiError } from "../../lib/api";
import { useAuthStore } from "../auth/authStore";
import * as tradingApi from "./tradingApi";
import { MarginModeToggle } from "./MarginModeToggle";
import type { MarginModeResponse } from "./tradingApi";

/**
 * STAGE-14E1 Task5 MarginModeToggle Vitest 套件。
 *
 * 覆盖：
 *  - 渲染当前账户 mode（ISOLATED → 逐仓 active）
 *  - canSwitch=false + blockers → 控件 disabled + 显示中文原因
 *  - 切换 → 确认 modal → 调 setMarginMode(targetMode)
 *  - setMarginMode 抛 30082（冷静期）→ 显示对应中文
 */

function renderToggle() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MarginModeToggle />
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

const baseMode: MarginModeResponse = {
  currentMode: "ISOLATED",
  modeChangedAt: null,
  coolingUntil: null,
  canSwitch: true,
  blockers: [],
  crossModeEnabled: true,
};

beforeEach(() => {
  setAuthSession();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  act(() => useAuthStore.getState().clearSession());
});

describe("MarginModeToggle", () => {
  it("renders current account mode (ISOLATED active)", async () => {
    vi.spyOn(tradingApi, "getMarginMode").mockResolvedValue(baseMode);

    renderToggle();

    const isolatedBtn = await screen.findByRole("button", { name: "逐仓" });
    await waitFor(() => expect(isolatedBtn).toHaveAttribute("aria-pressed", "true"));
    // 当前 mode 按钮自身 disabled（无需切到自己）
    expect(isolatedBtn).toBeDisabled();
  });

  it("disables switching and shows Chinese blockers when canSwitch=false", async () => {
    vi.spyOn(tradingApi, "getMarginMode").mockResolvedValue({
      ...baseMode,
      canSwitch: false,
      blockers: ["OPEN_POSITIONS", "ACTIVE_PENDING"],
    });

    renderToggle();

    // 目标 mode 按钮（全仓）应 disabled
    const crossBtn = await screen.findByRole("button", { name: "全仓" });
    await waitFor(() => expect(crossBtn).toBeDisabled());
    expect(screen.getByText(/有未平仓持仓、有未触发挂单/)).toBeInTheDocument();
  });

  it("opens confirm modal then calls setMarginMode(targetMode)", async () => {
    vi.spyOn(tradingApi, "getMarginMode").mockResolvedValue(baseMode);
    const setSpy = vi
      .spyOn(tradingApi, "setMarginMode")
      .mockResolvedValue({
        oldMode: "ISOLATED",
        newMode: "CROSS",
        modeChangedAt: "2026-06-02T00:00:00Z",
        coolingUntil: "2026-06-02T01:00:00Z",
      });

    renderToggle();

    const crossBtn = await screen.findByRole("button", { name: "全仓" });
    await waitFor(() => expect(crossBtn).toBeEnabled());
    await userEvent.click(crossBtn);

    // 确认 modal 出现
    expect(await screen.findByText("切换保证金模式")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: /确认切换/ }));

    await waitFor(() => expect(setSpy).toHaveBeenCalledWith("test-token", "CROSS"));
  });

  it("disables CROSS and shows 全仓暂未开放 when crossModeEnabled=false (canSwitch=true)", async () => {
    // 关键场景：无持仓/挂单（canSwitch=true）但平台未开放全仓 → 仍 disable CROSS + 提示
    vi.spyOn(tradingApi, "getMarginMode").mockResolvedValue({
      ...baseMode,
      canSwitch: true,
      blockers: [],
      crossModeEnabled: false,
    });

    renderToggle();

    const crossBtn = await screen.findByRole("button", { name: "全仓" });
    await waitFor(() => expect(crossBtn).toBeDisabled());
    expect(await screen.findByText("全仓暂未开放")).toBeInTheDocument();
    // 点击不应弹出确认 modal
    await userEvent.click(crossBtn);
    expect(screen.queryByText("切换保证金模式")).not.toBeInTheDocument();
  });

  it("enables CROSS when crossModeEnabled=true and not blocked", async () => {
    vi.spyOn(tradingApi, "getMarginMode").mockResolvedValue(baseMode);

    renderToggle();

    const crossBtn = await screen.findByRole("button", { name: "全仓" });
    await waitFor(() => expect(crossBtn).toBeEnabled());
    expect(screen.queryByText("全仓暂未开放")).not.toBeInTheDocument();
  });

  it("shows Chinese message when setMarginMode throws 30082 (cooling)", async () => {
    vi.spyOn(tradingApi, "getMarginMode").mockResolvedValue(baseMode);
    vi.spyOn(tradingApi, "setMarginMode").mockRejectedValue(
      new FalconApiError("30082", "Margin Mode Cooling Period Active", "trace-1", 400),
    );

    renderToggle();

    const crossBtn = await screen.findByRole("button", { name: "全仓" });
    await waitFor(() => expect(crossBtn).toBeEnabled());
    await userEvent.click(crossBtn);

    await userEvent.click(await screen.findByRole("button", { name: /确认切换/ }));

    expect(await screen.findByText(/处于切换冷静期，请稍后再试（30082）/)).toBeInTheDocument();
  });
});
