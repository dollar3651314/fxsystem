import type { ChartTimeframe } from "../chartTimeframes";
import type { KlineInterval } from "../marketTypes";

/**
 * TradingView 高级图表 ResolutionString ↔ 后端 KlineInterval 映射。
 *
 * TV 的 resolution 约定：纯数字 = 分钟（"60" = 1h），"1D" = 日线；
 * 后端 interval 约定见 {@link KlineInterval}（"1m"|"5m"|"15m"|"1h"|"4h"|"1d"）。
 * 两侧均为有限白名单，新增周期时必须同时更新两张表 + SUPPORTED_TV_RESOLUTIONS。
 */
const INTERVAL_TO_RESOLUTION: Record<KlineInterval, string> = {
  "1m": "1",
  "5m": "5",
  "15m": "15",
  "1h": "60",
  "4h": "240",
  "1d": "1D"
};

const RESOLUTION_TO_INTERVAL: Record<string, KlineInterval> = Object.fromEntries(
  Object.entries(INTERVAL_TO_RESOLUTION).map(([interval, resolution]) => [
    resolution,
    interval as KlineInterval
  ])
);

/** TV widget 声明支持的全部 resolution（顺序即 TV 周期菜单顺序）。 */
export const SUPPORTED_TV_RESOLUTIONS: string[] = Object.values(INTERVAL_TO_RESOLUTION);

/** 后端 interval → TV resolution。 */
export function intervalToResolution(interval: KlineInterval): string {
  return INTERVAL_TO_RESOLUTION[interval];
}

/** TV resolution → 后端 interval；未知 resolution 返回 null（调用方拒绝请求）。 */
export function resolutionToInterval(resolution: string): KlineInterval | null {
  return RESOLUTION_TO_INTERVAL[resolution] ?? null;
}

/**
 * 终端 ChartTimeframe → TV resolution。
 * "tick" 是经典图表专属（TV 高级图表无 tick 线模式），回退到 1m。
 */
export function timeframeToResolution(timeframe: ChartTimeframe): string {
  if (timeframe === "tick") {
    return INTERVAL_TO_RESOLUTION["1m"];
  }
  return INTERVAL_TO_RESOLUTION[timeframe];
}
