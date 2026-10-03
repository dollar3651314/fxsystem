import { useMemo } from "react";
import { formatPrice } from "./marketFormat";
import { useMarketStore } from "./marketStore";
import type { MarketSymbol } from "./marketTypes";

type MarketTickerProps = {
  /** 跑马灯展示的热门品种（resolveTickerSymbols 选出，调用方已并入 WS 订阅）。 */
  symbols: string[];
  /** symbols 元数据（取 pricePrecision 显示精度）。 */
  symbolsMeta?: MarketSymbol[];
};

/**
 * 顶栏热门品种跑马灯（2026-06-04）。
 *
 * 横向无缝滚动展示热门品种的实时 MID 报价，价格由 {@link useMarketStore} 的
 * WS price.tick 实时驱动（这些 symbol 由 TradingTerminal 并入 useMarketSocket 订阅，
 * 列表由管理端配置）。hover 暂停滚动。
 */
export function MarketTicker({ symbols, symbolsMeta }: MarketTickerProps) {
  const quotes = useMarketStore((s) => s.quotes);

  const precisionBySymbol = useMemo(() => {
    const map = new Map<string, number | undefined>();
    for (const meta of symbolsMeta ?? []) {
      map.set(meta.symbol, meta.pricePrecision);
    }
    return map;
  }, [symbolsMeta]);

  const items = useMemo(
    () =>
      symbols.map((sym) => {
        const mid = Number(quotes[sym]?.mid);
        return {
          symbol: sym,
          price: Number.isFinite(mid) ? formatPrice(mid, precisionBySymbol.get(sym)) : "—"
        };
      }),
    [symbols, quotes, precisionBySymbol]
  );

  if (!items.length) {
    return null;
  }

  // 两份相同内容拼接 → translateX(-50%) 实现无缝循环
  const groups = [0, 1];

  return (
    <div className="fx-ticker" aria-label="热门产品实时报价">
      <div className="fx-ticker__track">
        {groups.map((g) => (
          <div className="fx-ticker__group" key={g} aria-hidden={g === 1}>
            {items.map((item) => (
              <span className="fx-ticker__item" key={`${g}-${item.symbol}`}>
                <span className="fx-ticker__symbol">{item.symbol}</span>
                <span className="fx-ticker__price">
                  {item.price}
                </span>
              </span>
            ))}
          </div>
        ))}
      </div>
    </div>
  );
}
