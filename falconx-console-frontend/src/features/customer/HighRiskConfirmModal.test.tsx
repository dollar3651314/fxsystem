import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { HighRiskConfirmModal } from "./HighRiskConfirmModal";

const REASON_VALID = "撤销因风控规则触发的误操作以释放被冻结的余额";

afterEach(() => {
  cleanup();
});

function setup(overrides: Record<string, unknown> = {}) {
  const onSubmit = vi.fn().mockResolvedValue({ ok: true });
  const onSuccess = vi.fn();
  const onCancel = vi.fn();
  // 用 OKBTN 这种 ASCII 文案避免 AntD 给 CJK 文本插入空格（如 "确认" 渲染成 "确 认"）干扰按文本匹配
  const props = {
    open: true,
    title: "t",
    okText: "OKBTN",
    description: "d",
    details: [["字段 A", "值 A"]] as Array<[string, string]>,
    onSubmit,
    onSuccess,
    onCancel,
    ...overrides,
  };
  render(<HighRiskConfirmModal<{ ok: boolean }> {...(props as Parameters<typeof HighRiskConfirmModal<{ ok: boolean }>>[0])} />);
  return { onSubmit, onSuccess, onCancel };
}

function getOkButton(): HTMLButtonElement {
  const buttons = Array.from(document.querySelectorAll("button"));
  const btn = buttons.find(
    (b) => b.textContent?.includes("OKBTN") && !b.classList.contains("ant-modal-close"),
  );
  if (!btn) throw new Error("OK button not found in DOM");
  return btn as HTMLButtonElement;
}

describe("HighRiskConfirmModal (TC-HRC-FE-001 ~ 005)", () => {
  it("TC-HRC-FE-001: 仅 reason ≥10 字符即可提交（无 challenge / 无 checkbox）", async () => {
    const { onSubmit } = setup();
    expect(getOkButton().disabled).toBe(true);

    fireEvent.change(screen.getByPlaceholderText(/请详细说明操作原因/), {
      target: { value: REASON_VALID },
    });
    await waitFor(() => expect(getOkButton().disabled).toBe(false));

    fireEvent.click(getOkButton());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(REASON_VALID));
  });

  it("TC-HRC-FE-002 (§4 commit E): usernameChallenge 不匹配时按钮 disabled", async () => {
    setup({
      usernameChallenge: { expected: "alice", label: "请输入用户名 alice 确认" },
    });
    fireEvent.change(screen.getByPlaceholderText(/请详细说明操作原因/), {
      target: { value: REASON_VALID },
    });
    // reason 满足但 challenge 仍空 → disabled
    expect(getOkButton().disabled).toBe(true);

    // 输入错误的 challenge → 仍 disabled + 红色提示
    fireEvent.change(screen.getByPlaceholderText("请输入"), {
      target: { value: "bob" },
    });
    expect(getOkButton().disabled).toBe(true);
    expect(screen.getByText("挑战值不匹配")).toBeInTheDocument();
  });

  it("TC-HRC-FE-003 (§4 commit E): usernameChallenge 匹配后启用提交并透传 reason", async () => {
    const { onSubmit } = setup({
      usernameChallenge: { expected: "alice" },
    });
    fireEvent.change(screen.getByPlaceholderText(/请详细说明操作原因/), {
      target: { value: REASON_VALID },
    });
    fireEvent.change(screen.getByPlaceholderText("请输入"), {
      target: { value: "alice" },
    });
    await waitFor(() => expect(getOkButton().disabled).toBe(false));

    fireEvent.click(getOkButton());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(REASON_VALID));
  });

  it("TC-HRC-FE-004 (§4 commit E): challenge + checkbox 三重门同时满足才能提交", async () => {
    const { onSubmit } = setup({
      usernameChallenge: { expected: "alice" },
      requireConfirmCheckbox: true,
    });
    // 1) 只填 reason
    fireEvent.change(screen.getByPlaceholderText(/请详细说明操作原因/), {
      target: { value: REASON_VALID },
    });
    expect(getOkButton().disabled).toBe(true);
    // 2) 补 challenge
    fireEvent.change(screen.getByPlaceholderText("请输入"), {
      target: { value: "alice" },
    });
    expect(getOkButton().disabled).toBe(true);
    // 3) 勾 checkbox
    fireEvent.click(screen.getByRole("checkbox"));
    await waitFor(() => expect(getOkButton().disabled).toBe(false));

    fireEvent.click(getOkButton());
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });
});
