import type { KlineInterval, MarketChannel } from "./marketTypes";

export type ChartTimeframe = "tick" | KlineInterval;

export const CHART_TIMEFRAME_OPTIONS: Array<{
  value: ChartTimeframe;
  label: string;
}> = [
  { value: "tick", label: "Tick" },
  { value: "1m", label: "1m" },
  { value: "5m", label: "5m" },
  { value: "15m", label: "15m" },
  { value: "1h", label: "1h" },
  { value: "4h", label: "4h" },
  { value: "1d", label: "1d" }
];

const KLINE_TIMEFRAMES = new Set<KlineInterval>([
  "1m",
  "5m",
  "15m",
  "1h",
  "4h",
  "1d"
]);

const KLINE_TIMEFRAME_SECONDS: Record<KlineInterval, number> = {
  "1m": 60,
  "5m": 5 * 60,
  "15m": 15 * 60,
  "1h": 60 * 60,
  "4h": 4 * 60 * 60,
  "1d": 24 * 60 * 60
};

export function isKlineTimeframe(
  timeframe: ChartTimeframe
): timeframe is KlineInterval {
  return timeframe !== "tick" && KLINE_TIMEFRAMES.has(timeframe);
}

export function toKlineChannel(interval: KlineInterval): MarketChannel {
  return `kline.${interval}`;
}

export function timeframeBucketSeconds(timeframe: KlineInterval): number {
  return KLINE_TIMEFRAME_SECONDS[timeframe];
}
