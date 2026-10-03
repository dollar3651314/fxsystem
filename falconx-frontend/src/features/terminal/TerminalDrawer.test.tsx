import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TerminalDrawer } from "./TerminalDrawer";

afterEach(cleanup);

describe("TerminalDrawer", () => {
  it("renders three primary navigation buttons", () => {
    render(
      <TerminalDrawer
        onOpenProfile={() => {}}
        onOpenKyc={() => {}}
        onNavigateToWallet={() => {}}
      />
    );
    expect(screen.getByRole("button", { name: /账户资料/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /身份认证/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /入金/ })).toBeInTheDocument();
  });

  it("calls onOpenProfile when 账户资料 clicked", async () => {
    const handle = vi.fn();
    render(
      <TerminalDrawer
        onOpenProfile={handle}
        onOpenKyc={() => {}}
        onNavigateToWallet={() => {}}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /账户资料/ }));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("calls onOpenKyc when 身份认证 clicked", async () => {
    const handle = vi.fn();
    render(
      <TerminalDrawer
        onOpenProfile={() => {}}
        onOpenKyc={handle}
        onNavigateToWallet={() => {}}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /身份认证/ }));
    expect(handle).toHaveBeenCalledOnce();
  });

  it("calls onNavigateToWallet when 入金 clicked", async () => {
    const handle = vi.fn();
    render(
      <TerminalDrawer
        onOpenProfile={() => {}}
        onOpenKyc={() => {}}
        onNavigateToWallet={handle}
      />
    );
    await userEvent.click(screen.getByRole("button", { name: /入金/ }));
    expect(handle).toHaveBeenCalledOnce();
  });
});
