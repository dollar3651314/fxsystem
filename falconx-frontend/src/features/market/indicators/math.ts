/**
 * 技术指标数学库
 *
 * 全部纯函数，输入为按时间升序排好的 candle 数组（OHLC + UTCTimestamp），
 * 输出为 lightweight-charts 可直接 setData 的 `{ time, value }[]` 或多值数组。
 *
 * 设计原则：
 * - 不抛异常，遇到数据不足返回空数组或填 null
 * - 单 pass O(n)，避免每次 quote tick 重算开销
 * - 数值使用 number；调用端负责按 symbol pricePrecision 格式化
 * - VOL「用报价数量」目前无真实成交量数据 → 用 |close-open| * 10000 做 tick activity 代理
 *
 * 引用文档：
 *   MA/EMA: 维基 Moving average
 *   BOLL: Bollinger Bands (mid = SMA20, upper/lower = SMA ± 2σ)
 *   SAR:  Parabolic SAR (J. Welles Wilder)
 *   MACD: DIF=EMA12-EMA26, DEA=EMA9(DIF), MACD=2*(DIF-DEA)
 *   RSI:  14 周期相对强弱
 *   KDJ:  K=EMA(RSV,3), D=EMA(K,3), J=3K-2D
 *   WR:   Williams %R, 14 周期
 *   ZIG:  ZigZag 5% 阈值反转线
 */

import type { Time } from "lightweight-charts";

export type IndicatorBar = {
  time: Time;
  open: number;
  high: number;
  low: number;
  close: number;
};

export type LinePoint = { time: Time; value: number };

// === MA (SMA) === 简单移动平均
export function sma(candles: IndicatorBar[], period: number): LinePoint[] {
  const out: LinePoint[] = [];
  if (candles.length < period) return out;
  let s = 0;
  for (let i = 0; i < period - 1; i++) s += candles[i].close;
  for (let i = period - 1; i < candles.length; i++) {
    s += candles[i].close;
    out.push({ time: candles[i].time, value: s / period });
    s -= candles[i - period + 1].close;
  }
  return out;
}

// === EMA === 指数移动平均
export function ema(candles: IndicatorBar[], period: number): LinePoint[] {
  const out: LinePoint[] = [];
  if (candles.length < period) return out;
  const k = 2 / (period + 1);
  // 用前 period 个的 SMA 做种子值
  let seed = 0;
  for (let i = 0; i < period; i++) seed += candles[i].close;
  seed /= period;
  out.push({ time: candles[period - 1].time, value: seed });
  for (let i = period; i < candles.length; i++) {
    const prev = out[out.length - 1].value;
    const next = candles[i].close * k + prev * (1 - k);
    out.push({ time: candles[i].time, value: next });
  }
  return out;
}

// === BOLL === 布林带（上轨/中轨/下轨）
export type BollResult = {
  upper: LinePoint[];
  mid: LinePoint[];
  lower: LinePoint[];
};
export function boll(
  candles: IndicatorBar[],
  period = 20,
  mult = 2,
): BollResult {
  const upper: LinePoint[] = [];
  const mid: LinePoint[] = [];
  const lower: LinePoint[] = [];
  if (candles.length < period) return { upper, mid, lower };
  const window: number[] = [];
  let s = 0;
  for (let i = 0; i < candles.length; i++) {
    window.push(candles[i].close);
    s += candles[i].close;
    if (window.length > period) {
      s -= window.shift()!;
    }
    if (window.length === period) {
      const m = s / period;
      let varSum = 0;
      for (let j = 0; j < period; j++) {
        const d = window[j] - m;
        varSum += d * d;
      }
      const sd = Math.sqrt(varSum / period);
      mid.push({ time: candles[i].time, value: m });
      upper.push({ time: candles[i].time, value: m + mult * sd });
      lower.push({ time: candles[i].time, value: m - mult * sd });
    }
  }
  return { upper, mid, lower };
}

// === SAR === Parabolic SAR
// 步长 0.02，max 0.2（Wilder 默认）。返回的 value 是 SAR 点位（line series 渲染）。
export function sar(
  candles: IndicatorBar[],
  step = 0.02,
  max = 0.2,
): LinePoint[] {
  const out: LinePoint[] = [];
  if (candles.length < 3) return out;
  let isUp = candles[1].close > candles[0].close;
  let sarV = isUp ? candles[0].low : candles[0].high;
  let ep = isUp ? candles[0].high : candles[0].low; // extreme price
  let af = step; // acceleration factor
  for (let i = 1; i < candles.length; i++) {
    const c = candles[i];
    sarV = sarV + af * (ep - sarV);
    if (isUp) {
      if (c.low < sarV) {
        // 反转
        isUp = false;
        sarV = ep;
        ep = c.low;
        af = step;
      } else {
        if (c.high > ep) {
          ep = c.high;
          af = Math.min(af + step, max);
        }
      }
    } else {
      if (c.high > sarV) {
        isUp = true;
        sarV = ep;
        ep = c.high;
        af = step;
      } else {
        if (c.low < ep) {
          ep = c.low;
          af = Math.min(af + step, max);
        }
      }
    }
    out.push({ time: c.time, value: sarV });
  }
  return out;
}

// === ZigZag === 5% 反转阈值，把数据点连成 Z 字
export function zigzag(
  candles: IndicatorBar[],
  deviation = 0.05,
): LinePoint[] {
  const out: LinePoint[] = [];
  if (candles.length < 3) return out;
  let pivotIdx = 0;
  let pivotPrice = candles[0].close;
  let dir: "up" | "down" | null = null;
  for (let i = 1; i < candles.length; i++) {
    const c = candles[i];
    const change = (c.close - pivotPrice) / pivotPrice;
    if (dir === null) {
      if (Math.abs(change) >= deviation) {
        dir = change > 0 ? "up" : "down";
        out.push({ time: candles[pivotIdx].time, value: pivotPrice });
        pivotIdx = i;
        pivotPrice = c.close;
      }
    } else if (dir === "up") {
      if (c.close > pivotPrice) {
        pivotIdx = i;
        pivotPrice = c.close;
      } else if ((pivotPrice - c.close) / pivotPrice >= deviation) {
        out.push({ time: candles[pivotIdx].time, value: pivotPrice });
        dir = "down";
        pivotIdx = i;
        pivotPrice = c.close;
      }
    } else if (dir === "down") {
      if (c.close < pivotPrice) {
        pivotIdx = i;
        pivotPrice = c.close;
      } else if ((c.close - pivotPrice) / pivotPrice >= deviation) {
        out.push({ time: candles[pivotIdx].time, value: pivotPrice });
        dir = "up";
        pivotIdx = i;
        pivotPrice = c.close;
      }
    }
  }
  // 收尾：把最后一个 pivot 加进去，让线连到最右
  out.push({ time: candles[pivotIdx].time, value: pivotPrice });
  return out;
}

// === VOL === 成交量（用报价 tick 活动度代理）
// 真实 forex/cfd 缺少 volume；用 |close-open| 乘 10000 作活动度近似，可视化上能看出涨/跌区分
export type VolBar = { time: Time; value: number; color?: string };
export function vol(
  candles: IndicatorBar[],
  upColor = "rgba(22, 163, 74, 0.55)",
  downColor = "rgba(220, 38, 38, 0.55)",
): VolBar[] {
  return candles.map((c) => ({
    time: c.time,
    // 用 high-low（每根波动幅度）* 10000 作为 tick activity 代理
    value: Math.round((c.high - c.low) * 10000),
    color: c.close >= c.open ? upColor : downColor,
  }));
}

// === MACD === DIF / DEA / Histogram
export type MacdResult = {
  dif: LinePoint[];
  dea: LinePoint[];
  hist: VolBar[];
};
export function macd(
  candles: IndicatorBar[],
  fast = 12,
  slow = 26,
  signal = 9,
  upColor = "rgba(22, 163, 74, 0.65)",
  downColor = "rgba(220, 38, 38, 0.65)",
): MacdResult {
  const dif: LinePoint[] = [];
  const dea: LinePoint[] = [];
  const hist: VolBar[] = [];
  if (candles.length < slow + signal) return { dif, dea, hist };
  const fastEma = ema(candles, fast);
  const slowEma = ema(candles, slow);
  // align by time
  const slowMap = new Map(slowEma.map((p) => [p.time as number, p.value]));
  const difArr: LinePoint[] = [];
  for (const fp of fastEma) {
    const sv = slowMap.get(fp.time as number);
    if (sv != null) difArr.push({ time: fp.time, value: fp.value - sv });
  }
  // DEA = EMA(DIF, signal)
  const k = 2 / (signal + 1);
  if (difArr.length < signal) return { dif, dea, hist };
  let seed = 0;
  for (let i = 0; i < signal; i++) seed += difArr[i].value;
  seed /= signal;
  const deaArr: LinePoint[] = [];
  deaArr.push({ time: difArr[signal - 1].time, value: seed });
  for (let i = signal; i < difArr.length; i++) {
    const prev = deaArr[deaArr.length - 1].value;
    const next = difArr[i].value * k + prev * (1 - k);
    deaArr.push({ time: difArr[i].time, value: next });
  }
  // 把 difArr / deaArr 截到同一长度
  const start = signal - 1;
  for (let i = start; i < difArr.length; i++) {
    dif.push(difArr[i]);
  }
  for (const p of deaArr) dea.push(p);
  // hist = 2*(dif - dea)
  const deaMap = new Map(deaArr.map((p) => [p.time as number, p.value]));
  for (const dp of dif) {
    const dv = deaMap.get(dp.time as number);
    if (dv != null) {
      const h = (dp.value - dv) * 2;
      hist.push({ time: dp.time, value: h, color: h >= 0 ? upColor : downColor });
    }
  }
  return { dif, dea, hist };
}

// === RSI === 相对强弱
export function rsi(candles: IndicatorBar[], period = 14): LinePoint[] {
  const out: LinePoint[] = [];
  if (candles.length < period + 1) return out;
  let gainSum = 0;
  let lossSum = 0;
  for (let i = 1; i <= period; i++) {
    const diff = candles[i].close - candles[i - 1].close;
    if (diff >= 0) gainSum += diff;
    else lossSum -= diff;
  }
  let avgGain = gainSum / period;
  let avgLoss = lossSum / period;
  let rs = avgLoss === 0 ? 100 : avgGain / avgLoss;
  out.push({ time: candles[period].time, value: 100 - 100 / (1 + rs) });
  for (let i = period + 1; i < candles.length; i++) {
    const diff = candles[i].close - candles[i - 1].close;
    const g = diff > 0 ? diff : 0;
    const l = diff < 0 ? -diff : 0;
    avgGain = (avgGain * (period - 1) + g) / period;
    avgLoss = (avgLoss * (period - 1) + l) / period;
    rs = avgLoss === 0 ? 100 : avgGain / avgLoss;
    out.push({ time: candles[i].time, value: 100 - 100 / (1 + rs) });
  }
  return out;
}

// === KDJ === 9-3-3 stochastic
export type KdjResult = { k: LinePoint[]; d: LinePoint[]; j: LinePoint[] };
export function kdj(
  candles: IndicatorBar[],
  period = 9,
  smoothK = 3,
  smoothD = 3,
): KdjResult {
  const k: LinePoint[] = [];
  const d: LinePoint[] = [];
  const j: LinePoint[] = [];
  if (candles.length < period) return { k, d, j };
  let kPrev = 50;
  let dPrev = 50;
  for (let i = period - 1; i < candles.length; i++) {
    let hh = -Infinity;
    let ll = Infinity;
    for (let n = i - period + 1; n <= i; n++) {
      if (candles[n].high > hh) hh = candles[n].high;
      if (candles[n].low < ll) ll = candles[n].low;
    }
    const c = candles[i].close;
    const rsv = hh === ll ? 50 : ((c - ll) / (hh - ll)) * 100;
    // 维基定义：K=SMA(RSV, smoothK)，简化为 EMA-like 递推（中文 KDJ 习惯）
    const kv = (kPrev * (smoothK - 1) + rsv) / smoothK;
    const dv = (dPrev * (smoothD - 1) + kv) / smoothD;
    const jv = 3 * kv - 2 * dv;
    k.push({ time: candles[i].time, value: kv });
    d.push({ time: candles[i].time, value: dv });
    j.push({ time: candles[i].time, value: jv });
    kPrev = kv;
    dPrev = dv;
  }
  return { k, d, j };
}

// === WR === Williams %R（输出 -100..0，乘 -1 后到 0..100 更直观）
export function wr(candles: IndicatorBar[], period = 14): LinePoint[] {
  const out: LinePoint[] = [];
  if (candles.length < period) return out;
  for (let i = period - 1; i < candles.length; i++) {
    let hh = -Infinity;
    let ll = Infinity;
    for (let n = i - period + 1; n <= i; n++) {
      if (candles[n].high > hh) hh = candles[n].high;
      if (candles[n].low < ll) ll = candles[n].low;
    }
    const c = candles[i].close;
    const val = hh === ll ? -50 : ((hh - c) / (hh - ll)) * -100;
    // 输出 0..100 直观（值越小越超卖；和大多数中文 K 线 app 一致：80 上为超卖，20 下为超买）
    out.push({ time: candles[i].time, value: -val });
  }
  return out;
}
