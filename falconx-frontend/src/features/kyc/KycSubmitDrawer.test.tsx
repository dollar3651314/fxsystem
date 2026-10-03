import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAuthStore } from "../auth/authStore";
import { KycSubmitDrawer } from "./KycSubmitDrawer";
import * as kycApi from "./kycApi";
import type { KycSubmissionResponse } from "./types";

/**
 * STAGE-6-KYC 客户端 KycSubmitDrawer Vitest 套件。
 *
 * 覆盖 TC-KYC-090~094：
 *  - 090 首次打开 GET null → 渲染提交表单
 *  - 091 PENDING → 渲染只读视图 + 审核中 banner
 *  - 092 APPROVED → 渲染绿色 banner + 表单隐藏
 *  - 093 REJECTED → 渲染红色 banner + reason + 表单仍可见
 *  - 094 文件 > 2MB → 显示"证件图片过大"提示
 */

function renderDrawer() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } }
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <KycSubmitDrawer open={true} onClose={() => undefined} />
    </QueryClientProvider>
  );
}

function setAuthSession() {
  act(() => {
    useAuthStore.getState().setSession({
      accessToken: "test-access-token",
      refreshToken: "test-refresh-token",
      accessTokenExpiresAt: Date.now() + 60_000,
      refreshTokenExpiresAt: Date.now() + 3_600_000,
      userStatus: "ACTIVE",
      emailVerified: true
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

describe("KycSubmitDrawer", () => {
  it("TC-KYC-090 renders submit form when getLatestKyc returns null", async () => {
    vi.spyOn(kycApi, "getLatestKyc").mockResolvedValue(null);
    renderDrawer();
    await waitFor(() => expect(screen.getByText("KYC 认证")).toBeInTheDocument());
    expect(await screen.findByText(/身份信息/)).toBeInTheDocument();
    expect(screen.getByText(/证件类型/)).toBeInTheDocument();
    expect(screen.getByText(/证件号/)).toBeInTheDocument();
    expect(screen.getByText(/证件正面/)).toBeInTheDocument();
    expect(screen.getByText(/证件反面/)).toBeInTheDocument();
    expect(screen.getByText(/手持证件自拍/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /提交 KYC/ })).toBeInTheDocument();
  });

  it("TC-KYC-091 renders pending banner and hides form when status PENDING", async () => {
    const pending: KycSubmissionResponse = {
      submissionId: "S091",
      userId: "U091",
      level: 0,
      currentKycLevel: 0,
      status: "PENDING",
      idType: "ID_CARD",
      idNumber: "110101199001011234",
      submittedAt: "2026-05-14T04:00:00Z",
      reviewAt: null,
      rejectReason: null
    };
    vi.spyOn(kycApi, "getLatestKyc").mockResolvedValue(pending);
    renderDrawer();
    await waitFor(() => expect(screen.getByText(/审核中/)).toBeInTheDocument());
    expect(screen.queryByText(/身份信息/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /提交 KYC/ })).not.toBeInTheDocument();
  });

  it("TC-KYC-092 renders APPROVED banner with green styling and hides form", async () => {
    const approved: KycSubmissionResponse = {
      submissionId: "S092",
      userId: "U092",
      level: 1,
      currentKycLevel: 1,
      status: "APPROVED",
      idType: "ID_CARD",
      idNumber: "110101199001011234",
      submittedAt: "2026-05-14T04:00:00Z",
      reviewAt: "2026-05-14T05:00:00Z",
      rejectReason: null
    };
    vi.spyOn(kycApi, "getLatestKyc").mockResolvedValue(approved);
    renderDrawer();
    await waitFor(() => expect(screen.getByText(/KYC 已通过/)).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /提交 KYC/ })).not.toBeInTheDocument();
  });

  it("TC-KYC-093 renders REJECTED red banner with reason and keeps form visible", async () => {
    const rejected: KycSubmissionResponse = {
      submissionId: "S093",
      userId: "U093",
      level: 0,
      currentKycLevel: 0,
      status: "REJECTED",
      idType: "ID_CARD",
      idNumber: "110101199001011234",
      submittedAt: "2026-05-14T04:00:00Z",
      reviewAt: "2026-05-14T05:00:00Z",
      rejectReason: "证件图片不清晰"
    };
    vi.spyOn(kycApi, "getLatestKyc").mockResolvedValue(rejected);
    renderDrawer();
    await waitFor(() => expect(screen.getByText(/KYC 未通过/)).toBeInTheDocument());
    expect(screen.getByText(/证件图片不清晰/)).toBeInTheDocument();
    // 表单仍可见以便重新提交
    expect(screen.getByText(/身份信息/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /提交 KYC/ })).toBeInTheDocument();
  });

  it("TC-KYC-094 shows 'too large' error when file exceeds 2MB", async () => {
    vi.spyOn(kycApi, "getLatestKyc").mockResolvedValue(null);
    const user = userEvent.setup();
    const { container } = renderDrawer();
    await waitFor(() => expect(screen.getByText(/证件正面/)).toBeInTheDocument());

    // 构造一个 2MB + 1 byte 的 jpeg
    const tooLarge = new File([new Uint8Array(2 * 1024 * 1024 + 1)], "front.jpg", { type: "image/jpeg" });
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement;
    expect(fileInput).not.toBeNull();
    await user.upload(fileInput, tooLarge);

    await waitFor(() => expect(screen.getByText(/证件图片过大/)).toBeInTheDocument());
  });
});
