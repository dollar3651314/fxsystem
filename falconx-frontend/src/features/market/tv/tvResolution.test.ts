import { describe, expect, it } from "vitest";
import type { KlineInterval } from "../marketTypes";
import {
  intervalToResolution,
  resolutionToInterval,
  SUPPORTED_TV_RESOLUTIONS,
  timeframeToResolution
} from "./tvResolution";

describe("tvResolution", () => {
  it("interval → resolution 全表映射（分钟数字 + 1D）", () => {
    expect(intervalToResolution("1m")).toBe("1");
    expect(intervalToResolution("5m")).toBe("5");
    expect(intervalToResolution("15m")).toBe("15");
    expect(intervalToResolution("1h")).toBe("60");
    expect(intervalToResolution("4h")).toBe("240");
    expect(intervalToResolution("1d")).toBe("1D");
  });

  it("resolution → interval 与正向映射互逆", () => {
    const intervals: KlineInterval[] = ["1m", "5m", "15m", "1h", "4h", "1d"];
    for (const interval of intervals) {
      expect(resolutionToInterval(intervalToResolution(interval))).toBe(interval);
    }
  });

  it("未知 resolution 返回 null（调用方拒绝请求）", () => {
    expect(resolutionToInterval("2")).toBeNull();
    expect(resolutionToInterval("1W")).toBeNull();
    expect(resolutionToInterval("")).toBeNull();
  });

  it("SUPPORTED_TV_RESOLUTIONS 与映射表一致且无重复", () => {
    expect(SUPPORTED_TV_RESOLUTIONS).toEqual(["1", "5", "15", "60", "240", "1D"]);
    expect(new Set(SUPPORTED_TV_RESOLUTIONS).size).toBe(SUPPORTED_TV_RESOLUTIONS.length);
  });

  it("timeframe → resolution：tick 回退 1m（TV 无 tick 模式）", () => {
    expect(timeframeToResolution("tick")).toBe("1");
    expect(timeframeToResolution("4h")).toBe("240");
  });
});
