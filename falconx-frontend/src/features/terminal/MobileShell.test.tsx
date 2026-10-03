import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MobileShell } from "./MobileShell";

function mockWidth(width: number) {
  vi.stubGlobal("matchMedia", (query: string) => {
    const minMatch = query.match(/min-width:\s*(\d+)/);
    let matches = true;
    if (minMatch && width < parseInt(minMatch[1], 10)) matches = false;
    return {
      matches,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    };
  });
  vi.stubGlobal("innerWidth", width);
}

describe("MobileShell", () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  it("桌面（≥ md）退化为 fragment，不渲染 mobile shell wrapper", () => {
    mockWidth(1200);
    const { container } = render(
      <MobileShell activeKey="dashboard" onSelectKey={() => {}} navItems={[]} drawerContent={null}>
        <p>主内容</p>
      </MobileShell>
    );
    expect(container.querySelector(".fx-mobile-shell")).toBeNull();
    expect(screen.getByText("主内容")).toBeInTheDocument();
  });

  it("mobile (< md) 渲染 wrapper + bottom nav 占位", () => {
    mockWidth(375);
    const { container } = render(
      <MobileShell
        activeKey="dashboard"
        onSelectKey={() => {}}
        navItems={[{ key: "dashboard", label: "首页", icon: <span /> }]}
        drawerContent={<p>抽屉</p>}
      >
        <p>主内容</p>
      </MobileShell>
    );
    expect(container.querySelector(".fx-mobile-shell")).not.toBeNull();
    expect(container.querySelector(".fx-bottom-nav")).not.toBeNull();
    const mainElement = container.querySelector(".fx-mobile-shell__main");
    expect(mainElement).not.toBeNull();
    expect(mainElement?.textContent).toContain("主内容");
  });
});
