import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { getSymbols } from "./marketApi";

export interface SymbolPrecision {
  pricePrecision?: number;
  qtyPrecision?: number;
}

/**
 * 共享精度查询 hook：从 market /symbols 拉取 symbol → {pricePrecision, qtyPrecision} 映射，
 * 供「持仓 / 成交 / 挂单」等按行 symbol 格式化价格 / 数量用。
 *
 * queryKey 与 TradingTerminal 的 getSymbols 查询（["market","symbols",accessToken]）严格一致，
 * 以复用 React Query 缓存、不重复请求。token 取法亦与 TradingTerminal/PositionsTable 一致
 * （useAuthStore → session.accessToken）。
 *
 * 返回 (symbol) => { pricePrecision?, qtyPrecision? }；缺失符号回退 undefined
 * （formatPrice/formatQty 对 undefined 精度有缺省回退，不报错）。
 */
export function useSymbolPrecision(): (symbol: string | null | undefined) => SymbolPrecision {
  const session = useAuthStore((s) => s.session);
  const accessToken = session?.accessToken;

  const symbolsQuery = useQuery({
    queryKey: ["market", "symbols", accessToken],
    queryFn: () => getSymbols(accessToken ?? ""),
    enabled: Boolean(accessToken),
  });

  const map = useMemo(() => {
    const m = new Map<string, SymbolPrecision>();
    for (const s of symbolsQuery.data ?? []) {
      m.set(s.symbol, {
        pricePrecision: s.pricePrecision,
        // 后端两种字段名都可能出现，qtyPrecision 优先，回退 quantityPrecision
        qtyPrecision: s.qtyPrecision ?? s.quantityPrecision,
      });
    }
    return m;
  }, [symbolsQuery.data]);

  return useMemo(
    () => (symbol: string | null | undefined) => (symbol ? map.get(symbol) ?? {} : {}),
    [map],
  );
}
