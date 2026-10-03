/**
 * B 切片（2026-06-03）：杠杆/MM 档位客户端工具。
 *
 * 后端开仓按「名义价值(账户币) 落档」校验杠杆上限（30070），此前客户端只用
 * SymbolSpec.maxLeverage（=tier1 上限）做静态校验——跨档（大额仓位）时 UI 可选但必被拒。
 * 本模块按 GET /api/v1/trading/symbols/{symbol}/leverage-tiers 的档位表 + fxRate，
 * 用 notional(QC)×fxRate ≈ notional(AC) 动态解析当前档位上限（与后端风控同源口径；
 * 档位区间为万级宽度，估算汇率的瞬时差不足以错档）。
 */

export interface LeverageTierItem {
  tierNo: number;
  /** 档位名义价值下界（含，账户币） */
  notionalLower: string;
  /** 档位上界（不含，账户币）；null = 最高档无上限 */
  notionalUpper: string | null;
  maxLeverage: number;
  mmRate: string;
}

export interface LeverageTierListResponse {
  symbol: string;
  groupCode: string;
  quoteCurrency: string | null;
  /** QC→账户币当前汇率；同币种为 1；FX 不可用为 null（降级只按 tier1 上限） */
  fxRate: string | null;
  tiers: LeverageTierItem[];
}

export interface TierLeverageCap {
  /** 动态档位杠杆上限；无档位数据时 null（回退 SymbolSpec.maxLeverage） */
  cap: number | null;
  /** 命中档位号；降级（fxRate 缺失按 tier1）时为 1 */
  tierNo: number | null;
  /** true = fxRate 缺失，按 tier1 上限降级（无法精确落档） */
  degraded: boolean;
}

/** 按账户币 notional 落档（lower ≤ n < upper，tierNo 升序首个命中）。 */
export function resolveTierForNotional(
  tiers: LeverageTierItem[],
  notionalAc: number
): LeverageTierItem | null {
  for (const tier of tiers) {
    const lower = Number(tier.notionalLower);
    const upper = tier.notionalUpper == null ? Infinity : Number(tier.notionalUpper);
    if (notionalAc >= lower && notionalAc < upper) {
      return tier;
    }
  }
  return null;
}

/**
 * 解析当前名义价值下的档位杠杆上限。
 *
 * @param resp       档位接口响应（未加载/无配置时 null）
 * @param notionalQc 名义价值（计价币口径 = 数量 × 价格）；qty/价格未填时 null → 按 tier1
 */
export function resolveTierLeverageCap(
  resp: LeverageTierListResponse | null | undefined,
  notionalQc: number | null
): TierLeverageCap {
  if (!resp || resp.tiers.length === 0) {
    return { cap: null, tierNo: null, degraded: false };
  }
  const tier1 = resp.tiers[0];
  const fxRate = resp.fxRate == null ? null : Number(resp.fxRate);
  if (notionalQc == null || !(notionalQc > 0)) {
    // 未填数量：按最低档（tier1）展示上限，与既有静态行为一致
    return { cap: tier1.maxLeverage, tierNo: tier1.tierNo, degraded: false };
  }
  if (fxRate == null || !Number.isFinite(fxRate) || fxRate <= 0) {
    // FX 不可用：无法精确换算账户币 notional → 降级按 tier1 上限（后端 30070 仍兜底）
    return { cap: tier1.maxLeverage, tierNo: tier1.tierNo, degraded: true };
  }
  const notionalAc = notionalQc * fxRate;
  const hit = resolveTierForNotional(resp.tiers, notionalAc);
  if (!hit) {
    // 理论上 seed 区间连续不会落空；防御：取最高档上限
    const last = resp.tiers[resp.tiers.length - 1];
    return { cap: last.maxLeverage, tierNo: last.tierNo, degraded: false };
  }
  return { cap: hit.maxLeverage, tierNo: hit.tierNo, degraded: false };
}
