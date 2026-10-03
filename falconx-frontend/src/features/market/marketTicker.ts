import type { MarketSymbol } from "./marketTypes";

/**
 * 跑马灯热门品种选取（纯前端展示偏好，非 owner 产品数据）。
 *
 * owner 数据（有哪些可交易品种）仍来自 market `/symbols`；这里只是从中挑一个
 * 展示子集：优先取主力品种，∩ 真实存在且可交易，不足再按列表顺序补齐可交易品种。
 * 不硬编码返回任何 owner 数据——候选必须命中 `/symbols` 才会出现。
 */
const PREFERRED = [
  "BTCUSD",
  "ETHUSD",
  "XAUUSD",
  "EURUSD",
  "GBPUSD",
  "USDJPY",
  "BTCUSDT",
  "ETHUSDT",
  "XAGUSD",
  "AUDUSD"
];

function isTradable(symbol: MarketSymbol): boolean {
  return symbol.tradable !== false;
}

export function resolveTickerSymbols(symbols: MarketSymbol[], max = 8): string[] {
  if (!symbols.length) {
    return [];
  }
  const bySymbol = new Map(symbols.map((s) => [s.symbol, s]));
  const picked: string[] = [];
  const add = (sym: string) => {
    if (!picked.includes(sym) && picked.length < max) {
      picked.push(sym);
    }
  };

  for (const pref of PREFERRED) {
    const meta = bySymbol.get(pref);
    if (meta && isTradable(meta)) {
      add(pref);
    }
    if (picked.length >= max) {
      return picked;
    }
  }
  // 偏好不足：按 owner 列表顺序补齐可交易品种
  for (const meta of symbols) {
    if (isTradable(meta)) {
      add(meta.symbol);
    }
    if (picked.length >= max) {
      break;
    }
  }
  return picked;
}
