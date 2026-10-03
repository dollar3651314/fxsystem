import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { BottomNav } from "./BottomNav";
import { LayoutDashboard, BarChart3, WalletCards } from "lucide-react";

const items = [
  { key: "dashboard", label: "首页", icon: <LayoutDashboard size={20} /> },
  { key: "market", label: "行情", icon: <BarChart3 size={20} /> },
  { key: "wallet", label: "钱包", icon: <WalletCards size={20} /> },
];

describe("BottomNav", () => {
  it("渲染所有 items", () => {
    render(<BottomNav items={items} active="dashboard" onChange={() => {}} />);
    expect(screen.getByText("首页")).toBeInTheDocument();
    expect(screen.getByText("行情")).toBeInTheDocument();
    expect(screen.getByText("钱包")).toBeInTheDocument();
  });

  it("active item 加 .active class", () => {
    const { container } = render(<BottomNav items={items} active="market" onChange={() => {}} />);
    const buttons = container.querySelectorAll(".fx-bottom-nav__item");
    expect(buttons[0].className).not.toContain("active");
    expect(buttons[1].className).toContain("active");
    expect(buttons[2].className).not.toContain("active");
  });

  it("点击 item 触发 onChange", () => {
    const onChange = vi.fn();
    const { container } = render(<BottomNav items={items} active="dashboard" onChange={onChange} />);
    const buttons = container.querySelectorAll(".fx-bottom-nav__item");
    fireEvent.click(buttons[2]); // wallet is the third button
    expect(onChange).toHaveBeenCalledWith("wallet");
  });

  it("badge 大于 0 时显示数字", () => {
    const itemsWithBadge = [{ ...items[0], badge: 3 }, items[1], items[2]];
    render(<BottomNav items={itemsWithBadge} active="dashboard" onChange={() => {}} />);
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("badge 等于 0 不显示", () => {
    const itemsWithBadge = [{ ...items[0], badge: 0 }, items[1], items[2]];
    const { container } = render(<BottomNav items={itemsWithBadge} active="dashboard" onChange={() => {}} />);
    expect(container.querySelector(".fx-bottom-nav__badge")).toBeNull();
  });
});
