import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { MarginLevelIndicator } from "./MarginLevelIndicator";

/**
 * STAGE-14E1 Task6 MarginLevelIndicator Vitest 套件。
 *
 * 覆盖：
 *  - 三态各渲染对应中文标签 + badge 修饰类（颜色 token 通过 class 断言）
 *  - 百分比格式（"184" → "184.00%"）
 *  - null marginLevel / 未知 status → 显示 "—" / 无数据，不报错
 *  - hover/click 浮窗展开含中文说明 + 数值
 */

afterEach(() => cleanup());

describe("MarginLevelIndicator", () => {
  it("renders HEALTHY as green badge with percent", () => {
    render(<MarginLevelIndicator marginLevel="184.00" marginLevelStatus="HEALTHY" />);
    const badge = screen.getByRole("button");
    expect(badge.className).toContain("margin-level-indicator__badge--healthy");
    expect(screen.getByText("健康")).toBeInTheDocument();
    expect(screen.getByText("184.00%")).toBeInTheDocument();
  });

  it("renders MARGIN_CALL as risk/orange badge", () => {
    render(<MarginLevelIndicator marginLevel="105.50" marginLevelStatus="MARGIN_CALL" />);
    const badge = screen.getByRole("button");
    expect(badge.className).toContain("margin-level-indicator__badge--call");
    expect(screen.getByText("保证金预警")).toBeInTheDocument();
    expect(screen.getByText("105.50%")).toBeInTheDocument();
  });

  it("renders STOP_OUT as danger/red badge", () => {
    render(<MarginLevelIndicator marginLevel="80.00" marginLevelStatus="STOP_OUT" />);
    const badge = screen.getByRole("button");
    expect(badge.className).toContain("margin-level-indicator__badge--stopout");
    expect(screen.getByText("强平触发")).toBeInTheDocument();
    expect(screen.getByText("80.00%")).toBeInTheDocument();
  });

  it("formats integer-like marginLevel to 2 decimals with percent sign", () => {
    render(<MarginLevelIndicator marginLevel="200" marginLevelStatus="HEALTHY" />);
    expect(screen.getByText("200.00%")).toBeInTheDocument();
  });

  it("shows — and 无数据 when marginLevel/status are null (graceful degrade)", () => {
    render(<MarginLevelIndicator marginLevel={null} marginLevelStatus={null} />);
    const badge = screen.getByRole("button");
    expect(badge.className).toContain("margin-level-indicator__badge--unknown");
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText("无数据")).toBeInTheDocument();
  });

  it("shows — when marginLevel is non-numeric", () => {
    render(<MarginLevelIndicator marginLevel="abc" marginLevelStatus="HEALTHY" />);
    expect(screen.getByText("—")).toBeInTheDocument();
  });

  it("opens popover with Chinese description and value on click", async () => {
    render(<MarginLevelIndicator marginLevel="105.50" marginLevelStatus="MARGIN_CALL" />);
    await userEvent.click(screen.getByRole("button"));
    const pop = screen.getByRole("tooltip");
    expect(pop).toBeInTheDocument();
    expect(pop.textContent).toContain("保证金水平偏低");
    expect(pop.textContent).toContain("105.50%");
  });
});
