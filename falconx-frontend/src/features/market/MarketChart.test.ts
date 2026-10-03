import { describe, expect, it } from "vitest";
import { resolveInitialVisibleLogicalRange } from "./marketChartViewport";

describe("MarketChart viewport", () => {
  it("keeps sparse kline data from filling the whole chart", () => {
    expect(resolveInitialVisibleLogicalRange(4)).toEqual({
      from: -68,
      to: 12
    });
  });

  it("shows the latest window plus right-side room for dense kline data", () => {
    expect(resolveInitialVisibleLogicalRange(120)).toEqual({
      from: 48,
      to: 128
    });
  });
});
