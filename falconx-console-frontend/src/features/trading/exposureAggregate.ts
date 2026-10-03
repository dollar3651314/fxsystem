import type { ExposureItem } from "./types";

/** quoteCurrency 缺失（过渡期）时的聚合分组占位标签。 */
export const UNKNOWN_QUOTE_LABEL = "未知";

/**
 * 按报价币聚合后的一行。
 *
 * STAGE-14E2 Task5：跨 symbol 求和只用 netExposureUsd（已统一折算 USD 等价，无 mixed-currency
 * 问题），不复用有缺陷的原币 netExposure 求和。多/空敞口同样取 USD 等价分量。
 */
export interface ExposureByQuoteRow {
  quoteCurrency: string;
  symbolCount: number;
  /** Σ netExposureUsd（USD 等价净敞口） */
  netExposureUsd: number;
  /** Σ max(netExposureUsd, 0)（USD 等价多头分量） */
  longExposureUsd: number;
  /** Σ -min(netExposureUsd, 0)（USD 等价空头分量，取正） */
  shortExposureUsd: number;
  /** 本组 |netExposureUsd| 占全部组 Σ|netExposureUsd| 的占比（0~1） */
  share: number;
}

/**
 * 把 per-symbol exposure 列表按 quoteCurrency 聚合。
 *
 * netExposureUsd 缺失/非数按 0 计。降序按 |netExposureUsd|。
 */
export function aggregateByQuoteCurrency(items: ExposureItem[]): ExposureByQuoteRow[] {
  const groups = new Map<string, { net: number; long: number; short: number; count: number }>();
  for (const it of items) {
    const key = it.quoteCurrency && it.quoteCurrency.trim() ? it.quoteCurrency : UNKNOWN_QUOTE_LABEL;
    const usd = Number(it.netExposureUsd);
    const net = Number.isFinite(usd) ? usd : 0;
    const g = groups.get(key) ?? { net: 0, long: 0, short: 0, count: 0 };
    g.net += net;
    g.long += net > 0 ? net : 0;
    g.short += net < 0 ? -net : 0;
    g.count += 1;
    groups.set(key, g);
  }
  const totalAbs = Array.from(groups.values()).reduce((acc, g) => acc + Math.abs(g.net), 0);
  return Array.from(groups.entries())
    .map(([quoteCurrency, g]) => ({
      quoteCurrency,
      symbolCount: g.count,
      netExposureUsd: g.net,
      longExposureUsd: g.long,
      shortExposureUsd: g.short,
      share: totalAbs > 0 ? Math.abs(g.net) / totalAbs : 0,
    }))
    .sort((a, b) => Math.abs(b.netExposureUsd) - Math.abs(a.netExposureUsd));
}
