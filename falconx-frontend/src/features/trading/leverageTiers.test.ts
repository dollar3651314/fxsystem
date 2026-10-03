import { describe, expect, it } from "vitest";
import {
  resolveTierForNotional,
  resolveTierLeverageCap,
  type LeverageTierListResponse,
} from "./leverageTiers";

// AUDCAD T6（V38 后）：tier1 0-5万 300x / tier2 5万-25万 100x / tier3 25万-100万 50x / tier4 100万+ 20x
const AUDCAD: LeverageTierListResponse = {
  symbol: "AUDCAD",
  groupCode: "default",
  quoteCurrency: "CAD",
  fxRate: "0.72250000",
  tiers: [
    { tierNo: 1, notionalLower: "0", notionalUpper: "50000", maxLeverage: 300, mmRate: "0.001650" },
    { tierNo: 2, notionalLower: "50000", notionalUpper: "250000", maxLeverage: 100, mmRate: "0.005000" },
    { tierNo: 3, notionalLower: "250000", notionalUpper: "1000000", maxLeverage: 50, mmRate: "0.010000" },
    { tierNo: 4, notionalLower: "1000000", notionalUpper: null, maxLeverage: 20, mmRate: "0.025000" },
  ],
};

describe("leverageTiers", () => {
  it("resolveTierForNotional 按 [lower, upper) 落档，最高档无上限", () => {
    expect(resolveTierForNotional(AUDCAD.tiers, 0)?.tierNo).toBe(1);
    expect(resolveTierForNotional(AUDCAD.tiers, 49999.99)?.tierNo).toBe(1);
    expect(resolveTierForNotional(AUDCAD.tiers, 50000)?.tierNo).toBe(2); // 下界含
    expect(resolveTierForNotional(AUDCAD.tiers, 999999)?.tierNo).toBe(3);
    expect(resolveTierForNotional(AUDCAD.tiers, 5_000_000)?.tierNo).toBe(4);
  });

  it("小额名义价值落 tier1（与既有静态上限一致）", () => {
    // 100 AUDCAD × 0.99 ≈ 99 CAD × 0.7225 ≈ 71.5 USDT → tier1
    const r = resolveTierLeverageCap(AUDCAD, 99);
    expect(r).toEqual({ cap: 300, tierNo: 1, degraded: false });
  });

  it("跨档名义价值动态降档（修 UI 可选但后端 30070 的脱节）", () => {
    // 100,000 CAD × 0.7225 = 72,250 USDT → tier2 → 100x
    expect(resolveTierLeverageCap(AUDCAD, 100_000)).toEqual({ cap: 100, tierNo: 2, degraded: false });
    // 2,000,000 CAD × 0.7225 = 1,445,000 USDT → tier4 → 20x
    expect(resolveTierLeverageCap(AUDCAD, 2_000_000)).toEqual({ cap: 20, tierNo: 4, degraded: false });
  });

  it("未填数量按 tier1 展示上限", () => {
    expect(resolveTierLeverageCap(AUDCAD, null)).toEqual({ cap: 300, tierNo: 1, degraded: false });
    expect(resolveTierLeverageCap(AUDCAD, 0)).toEqual({ cap: 300, tierNo: 1, degraded: false });
  });

  it("fxRate 缺失降级按 tier1 上限（degraded=true，后端 30070 兜底）", () => {
    const noFx = { ...AUDCAD, fxRate: null };
    expect(resolveTierLeverageCap(noFx, 1_000_000)).toEqual({ cap: 300, tierNo: 1, degraded: true });
  });

  it("无档位数据回退 null（沿用 SymbolSpec.maxLeverage）", () => {
    expect(resolveTierLeverageCap(null, 100)).toEqual({ cap: null, tierNo: null, degraded: false });
    expect(resolveTierLeverageCap({ ...AUDCAD, tiers: [] }, 100)).toEqual({ cap: null, tierNo: null, degraded: false });
  });

  it("同币种 fxRate=1 直接用 QC 名义价值落档", () => {
    const usdt = { ...AUDCAD, quoteCurrency: "USDT", fxRate: "1" };
    expect(resolveTierLeverageCap(usdt, 60_000)).toEqual({ cap: 100, tierNo: 2, degraded: false });
  });
});
