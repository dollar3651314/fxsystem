import { formatMarketGroup } from "./marketFormat";
import type { MarketSymbol } from "./marketTypes";

export type MarketSymbolGroup = {
  key: string;
  label: string;
  symbols: MarketSymbol[];
};

const MARKET_GROUP_ORDER = ["FX", "METAL", "ENERGY", "INDEX", "STOCK", "CRYPTO", "CFD"];

export function groupMarketSymbols(symbols: MarketSymbol[]): MarketSymbolGroup[] {
  const grouped = new Map<string, MarketSymbol[]>();

  for (const symbol of symbols) {
    const label = formatMarketGroup(symbol);
    const bucket = grouped.get(label) ?? [];
    bucket.push(symbol);
    grouped.set(label, bucket);
  }

  return [...grouped.entries()]
    .map(([label, groupSymbols]) => ({
      key: label,
      label,
      symbols: [...groupSymbols].sort(compareMarketSymbols)
    }))
    .sort((left, right) => marketGroupRank(left.label) - marketGroupRank(right.label));
}

export function resolveActiveMarketGroup(
  groups: MarketSymbolGroup[],
  activeGroupKey: string | null
): MarketSymbolGroup | undefined {
  if (!groups.length) {
    return undefined;
  }

  return groups.find((group) => group.key === activeGroupKey) ?? groups[0];
}

export function resolveMarketGroupSymbol(
  groups: MarketSymbolGroup[],
  activeGroupKey: string | null,
  selectedSymbol: string | null
): string | null {
  const activeGroup = resolveActiveMarketGroup(groups, activeGroupKey);
  if (!activeGroup?.symbols.length) {
    return null;
  }

  if (
    selectedSymbol &&
    activeGroup.symbols.some((symbol) => symbol.symbol === selectedSymbol)
  ) {
    return selectedSymbol;
  }

  return resolvePreferredMarketSymbol(activeGroup)?.symbol ?? null;
}

export function resolvePreferredMarketSymbol(
  group: MarketSymbolGroup | undefined
): MarketSymbol | undefined {
  return (
    group?.symbols.find((symbol) => symbol.priceStatus !== "MISSING") ??
    group?.symbols[0]
  );
}

function marketGroupRank(label: string): number {
  const index = MARKET_GROUP_ORDER.indexOf(label);
  return index === -1 ? MARKET_GROUP_ORDER.length : index;
}

function compareMarketSymbols(left: MarketSymbol, right: MarketSymbol): number {
  const statusRank = priceStatusRank(left) - priceStatusRank(right);
  if (statusRank !== 0) {
    return statusRank;
  }

  return left.symbol.localeCompare(right.symbol, "en");
}

function priceStatusRank(symbol: MarketSymbol): number {
  if (symbol.tradable || symbol.priceStatus === "LIVE") {
    return 0;
  }
  if (symbol.priceStatus === "REFERENCE") {
    return 1;
  }
  return 2;
}
