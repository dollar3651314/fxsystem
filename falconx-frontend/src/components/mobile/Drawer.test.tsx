import { describe, it, expect, vi } from "vitest";
import { render, fireEvent } from "@testing-library/react";
import { Drawer } from "./Drawer";

describe("Drawer", () => {
  it("open=false 时不渲染 overlay open class", () => {
    const { container } = render(<Drawer open={false} onClose={() => {}}><p>内容</p></Drawer>);
    expect(container.querySelector(".fx-drawer-overlay")?.className).not.toContain("open");
  });

  it("open=true 时 overlay 和 drawer 都加 open class", () => {
    const { container } = render(<Drawer open={true} onClose={() => {}}><p>内容</p></Drawer>);
    expect(container.querySelector(".fx-drawer-overlay")?.className).toContain("open");
    expect(container.querySelector(".fx-drawer")?.className).toContain("open");
  });

  it("点击 overlay 触发 onClose", () => {
    const onClose = vi.fn();
    const { container } = render(<Drawer open={true} onClose={onClose}><p>内容</p></Drawer>);
    fireEvent.click(container.querySelector(".fx-drawer-overlay")!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("点击内容不触发 onClose（stopPropagation）", () => {
    const onClose = vi.fn();
    const { container } = render(<Drawer open={true} onClose={onClose}><p>内容</p></Drawer>);
    const content = container.querySelector(".fx-drawer p");
    fireEvent.click(content!);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("按 ESC 触发 onClose", () => {
    const onClose = vi.fn();
    render(<Drawer open={true} onClose={onClose}><p>内容</p></Drawer>);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("open=false 时按 ESC 不触发 onClose", () => {
    const onClose = vi.fn();
    render(<Drawer open={false} onClose={onClose}><p>内容</p></Drawer>);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
  });
});
