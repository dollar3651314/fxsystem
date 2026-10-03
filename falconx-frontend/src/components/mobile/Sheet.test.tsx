import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { Sheet } from "./Sheet";

afterEach(() => cleanup());

describe("Sheet", () => {
  it("open=false 时 sheet 不加 open class", () => {
    const { container } = render(<Sheet open={false} onClose={() => {}}><p>内容</p></Sheet>);
    expect(container.querySelector(".fx-sheet")?.className).not.toContain("open");
  });

  it("open=true 时 sheet 加 open class", () => {
    const { container } = render(<Sheet open={true} onClose={() => {}}><p>内容</p></Sheet>);
    expect(container.querySelector(".fx-sheet")?.className).toContain("open");
  });

  it("点击 overlay 触发 onClose", () => {
    const onClose = vi.fn();
    const { container } = render(<Sheet open={true} onClose={onClose}><p>内容</p></Sheet>);
    fireEvent.click(container.querySelector(".fx-sheet-overlay")!);
    expect(onClose).toHaveBeenCalled();
  });

  it("ESC 关闭", () => {
    const onClose = vi.fn();
    render(<Sheet open={true} onClose={onClose}><p>内容</p></Sheet>);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalled();
  });

  it("title 渲染", () => {
    render(<Sheet open={true} onClose={() => {}} title="下单确认"><p>表单内容</p></Sheet>);
    expect(screen.getByText("下单确认")).toBeInTheDocument();
  });

  it("点击关闭按钮触发 onClose", () => {
    const onClose = vi.fn();
    render(<Sheet open={true} onClose={onClose} title="下单"><p>内容</p></Sheet>);
    fireEvent.click(screen.getByRole("button", { name: "关闭" }));
    expect(onClose).toHaveBeenCalled();
  });
});
