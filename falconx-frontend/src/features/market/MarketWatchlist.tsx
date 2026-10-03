import { Loader2, Search, X } from "lucide-react";
import {
  type CSSProperties,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState
} from "react";
import {
  formatMarketGroup,
  formatPriceStatusLabel,
  formatPrice,
  formatTimestamp,
  isTradable,
  resolveAskPrice,
  resolveBidPrice,
  resolveQuoteDisplayTimestamp,
  resolvePriceStatus
} from "./marketFormat";
import type { MarketConnectionState, MarketSymbol, Quote } from "./marketTypes";
import type { MarketSymbolGroup } from "./marketSymbolGroups";

type MarketWatchlistProps = {
  groups: MarketSymbolGroup[] | undefined;
  activeGroupKey: string | null;
  quotes: Record<string, Quote>;
  selectedSymbol: string | null;
  connectionState?: MarketConnectionState;
  isLoading: boolean;
  isError?: boolean;
  errorMessage?: string;
  onRetry?: () => void;
  onSelectSymbol: (symbol: string) => void;
  onSelectGroup: (groupKey: string) => void;
  onVisibleSymbolsChange?: (symbols: string[]) => void;
};

export function MarketWatchlist({
  groups,
  activeGroupKey,
  quotes,
  selectedSymbol,
  connectionState = "idle",
  isLoading,
  isError,
  errorMessage,
  onRetry,
  onSelectSymbol,
  onSelectGroup,
  onVisibleSymbolsChange
}: MarketWatchlistProps) {
  const marketGroups = groups ?? [];
  const activeGroup = resolveActiveGroup(marketGroups, activeGroupKey);
  const symbolCount = marketGroups.reduce((count, group) => count + group.symbols.length, 0);
  const allActiveSymbols = useMemo(() => activeGroup?.symbols ?? [], [activeGroup]);

  // 2026-05-20: 搜索框下沉到市场页，按 symbol / category / base/quote 子串模糊过滤
  const [searchQuery, setSearchQuery] = useState("");
  const activeSymbols = useMemo(() => {
    const q = searchQuery.trim().toUpperCase();
    if (!q) return allActiveSymbols;
    return allActiveSymbols.filter((s) =>
      s.symbol.toUpperCase().includes(q) ||
      (s.baseCurrency ?? "").toUpperCase().includes(q) ||
      (s.quoteCurrency ?? "").toUpperCase().includes(q)
    );
  }, [allActiveSymbols, searchQuery]);
  const rowsRootRef = useRef<HTMLDivElement | null>(null);
  const tabElementsRef = useRef(new Map<string, HTMLButtonElement>());
  const rowElementsRef = useRef(new Map<string, HTMLButtonElement>());
  const visibleSymbolsRef = useRef(new Set<string>());
  const lastReportedSymbolsRef = useRef<string[]>([]);
  const [virtualMetrics, setVirtualMetrics] = useState<VirtualMetrics>({
    scrollTop: 0,
    viewportHeight: DEFAULT_VIRTUAL_VIEWPORT_HEIGHT
  });
  const shouldVirtualizeRows = activeSymbols.length > VIRTUALIZATION_THRESHOLD;

  const updateVirtualMetrics = useCallback(() => {
    const root = rowsRootRef.current;
    if (!root) {
      return;
    }

    const nextMetrics = {
      scrollTop: root.scrollTop,
      viewportHeight: root.clientHeight || DEFAULT_VIRTUAL_VIEWPORT_HEIGHT
    };
    setVirtualMetrics((prev) =>
      prev.scrollTop === nextMetrics.scrollTop &&
      prev.viewportHeight === nextMetrics.viewportHeight
        ? prev
        : nextMetrics
    );
  }, []);

  const virtualWindow = useMemo(
    () =>
      resolveVirtualWindow(
        activeSymbols,
        shouldVirtualizeRows,
        virtualMetrics
      ),
    [activeSymbols, shouldVirtualizeRows, virtualMetrics]
  );

  const reportVisibleSymbols = useCallback(() => {
    if (!onVisibleSymbolsChange) {
      return;
    }

    const orderedSymbols = activeSymbols
      .map((symbol) => symbol.symbol)
      .filter((symbol) => visibleSymbolsRef.current.has(symbol));

    if (sameStringList(orderedSymbols, lastReportedSymbolsRef.current)) {
      return;
    }

    lastReportedSymbolsRef.current = orderedSymbols;
    onVisibleSymbolsChange(orderedSymbols);
  }, [activeSymbols, onVisibleSymbolsChange]);

  useEffect(() => {
    const activeTab = activeGroup?.key
      ? tabElementsRef.current.get(activeGroup.key)
      : undefined;
    // 只在分类 tab 横向滚动条内居中 active tab。原先用 element.scrollIntoView()
    // 会递归滚动所有可滚动祖先（含整列容器），选中靠后分类时把整个市场列表
    // 横向/纵向滚走，造成「列表偏移、左侧被裁」。这里改成只操作 tabs 容器自身的
    // scrollLeft，绝不触碰任何祖先。
    const tabsContainer = activeTab?.parentElement;
    if (activeTab && tabsContainer && typeof tabsContainer.scrollTo === "function") {
      const target =
        activeTab.offsetLeft -
        (tabsContainer.clientWidth - activeTab.clientWidth) / 2;
      tabsContainer.scrollTo({
        left: Math.max(0, target),
        behavior: "smooth",
      });
    }
  }, [activeGroup?.key]);

  useEffect(() => {
    const root = rowsRootRef.current;
    if (!root) {
      return;
    }

    root.scrollTop = 0;
    updateVirtualMetrics();
  }, [activeGroup?.key, searchQuery, updateVirtualMetrics]);

  useEffect(() => {
    if (!shouldVirtualizeRows) {
      return;
    }

    updateVirtualMetrics();
    const root = rowsRootRef.current;
    if (!root) {
      return;
    }

    if (typeof ResizeObserver !== "undefined") {
      const observer = new ResizeObserver(updateVirtualMetrics);
      observer.observe(root);
      return () => observer.disconnect();
    }

    window.addEventListener("resize", updateVirtualMetrics);
    return () => window.removeEventListener("resize", updateVirtualMetrics);
  }, [shouldVirtualizeRows, updateVirtualMetrics]);

  useEffect(() => {
    if (!onVisibleSymbolsChange) {
      return;
    }

    visibleSymbolsRef.current = new Set();
    lastReportedSymbolsRef.current = [];
    const fallbackSymbols = activeSymbols
      .slice(0, VISIBLE_SYMBOL_FALLBACK_COUNT)
      .map((symbol) => symbol.symbol);

    if (typeof IntersectionObserver === "undefined") {
      onVisibleSymbolsChange(fallbackSymbols);
      lastReportedSymbolsRef.current = fallbackSymbols;
      return;
    }

    const root = rowsRootRef.current;
    if (!root) {
      onVisibleSymbolsChange(fallbackSymbols);
      lastReportedSymbolsRef.current = fallbackSymbols;
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const symbol = (entry.target as HTMLElement).dataset.symbol;
          if (!symbol) {
            continue;
          }

          if (entry.isIntersecting) {
            visibleSymbolsRef.current.add(symbol);
          } else {
            visibleSymbolsRef.current.delete(symbol);
          }
        }
        reportVisibleSymbols();
      },
      {
        root,
        rootMargin: "240px 0px",
        threshold: 0.01
      }
    );

    for (const symbol of virtualWindow.renderedSymbols) {
      const row = rowElementsRef.current.get(symbol.symbol);
      if (row) {
        observer.observe(row);
      }
    }

    return () => observer.disconnect();
  }, [activeSymbols, onVisibleSymbolsChange, reportVisibleSymbols, virtualWindow.renderedSymbols]);

  const connectionInfo = describeConnection(connectionState);

  const renderRow = (symbol: MarketSymbol, style?: CSSProperties) => {
    const quote = quotes[symbol.symbol];
    const status = resolvePriceStatus(symbol, quote);
    const tradable = isTradable(symbol, quote);
    const quoteTimestamp = resolveQuoteDisplayTimestamp(symbol, quote);
    const quoteTimestampLabel = quote?.receivedAt ? "收到" : "报价";
    const statusLabel = formatPriceStatusLabel(tradable, status);

    return (
      <button
        className={[
          "market-row",
          selectedSymbol === symbol.symbol ? "selected" : "",
          status.toLowerCase()
        ]
          .filter(Boolean)
          .join(" ")}
        type="button"
        key={symbol.symbol}
        data-symbol={symbol.symbol}
        ref={(node) => {
          if (node) {
            rowElementsRef.current.set(symbol.symbol, node);
          } else {
            rowElementsRef.current.delete(symbol.symbol);
          }
        }}
        style={style}
        onClick={() => onSelectSymbol(symbol.symbol)}
      >
        <span>
          <strong>{symbol.symbol}</strong>
          <small>{formatSymbolDetails(symbol)}</small>
        </span>
        <span className="market-row__quote">
          <b>
            <small>Bid</small>
            {formatPrice(resolveBidPrice(symbol, quote), symbol.pricePrecision)}
          </b>
          <b>
            <small>Ask</small>
            {formatPrice(resolveAskPrice(symbol, quote), symbol.pricePrecision)}
          </b>
          <em aria-label={`行情状态：${statusLabel}`} title={statusLabel}>
            {statusLabel}
          </em>
          <time dateTime={quoteTimestamp}>
            {quoteTimestampLabel} {formatTimestamp(quoteTimestamp)}
          </time>
        </span>
      </button>
    );
  };

  return (
    <section className="market-list" aria-label="市场列表">
      <div className="fx-panel-head">
        <span className="fx-panel-head__num">§01</span>
        <h2 className="fx-panel-head__title">市场</h2>
        <span className="fx-panel-head__hint">
          {/* 行情连接状态：6 个状态分别用 cyan / lime / risk / short 颜色脉冲点表示 */}
          <span
            className={`market-list__conn-dot market-list__conn-dot--${connectionInfo.state}`}
            title={connectionInfo.label}
            aria-label={connectionInfo.label}
          />
          {symbolCount ? (
            <>
              <b>{symbolCount.toLocaleString()}</b> 个品种
            </>
          ) : (
            "LIVE / 参考"
          )}
        </span>
      </div>

      {/* 2026-05-20: 搜索从 topbar 下沉到这里，只在市场页可见 */}
      <div className="market-list__search">
        <Search size={13} strokeWidth={2} aria-hidden="true" />
        <input
          type="search"
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          placeholder="搜索 symbol / base / quote …"
          aria-label="搜索品种"
        />
        {searchQuery && (
          <button
            type="button"
            className="market-list__search-clear"
            onClick={() => setSearchQuery("")}
            aria-label="清除搜索"
          >
            <X size={12} strokeWidth={2.2} aria-hidden="true" />
          </button>
        )}
      </div>

      {marketGroups.length ? (
        <div className="market-list__tabs-wrap">
          <span className="market-list__tabs-label" aria-hidden="true">分类</span>
          <div className="market-list__tabs" role="tablist" aria-label="市场分类">
            {marketGroups.map((group) => (
              <button
                key={group.key}
                type="button"
                role="tab"
                aria-selected={group.key === activeGroup?.key}
                className={group.key === activeGroup?.key ? "active" : undefined}
                ref={(node) => {
                  if (node) {
                    tabElementsRef.current.set(group.key, node);
                  } else {
                    tabElementsRef.current.delete(group.key);
                  }
                }}
                onClick={() => onSelectGroup(group.key)}
              >
                <span>{group.label}</span>
                <b>{group.symbols.length}</b>
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {isLoading ? (
        <div className="panel-empty">
          <Loader2 size={16} aria-hidden="true" />
          加载市场产品...
        </div>
      ) : null}

      {!isLoading && isError && !marketGroups.length ? (
        <div className="market-list__error">
          <span className="market-list__error-title">市场数据加载失败</span>
          {errorMessage ? <span className="market-list__error-msg">{errorMessage}</span> : null}
          <span className="market-list__error-hint">后端 market-service 可能短暂不可用</span>
          {onRetry ? (
            <button type="button" className="fx-ghost-btn fx-ghost-btn--primary" onClick={onRetry}>
              重试
            </button>
          ) : null}
        </div>
      ) : null}

      {!isLoading && !isError && !marketGroups.length ? (
        <div className="panel-empty">暂无可展示市场，请确认网关与 market-service 已启动。</div>
      ) : null}

      <div
        className={[
          "market-list__rows",
          shouldVirtualizeRows ? "market-list__rows--virtualized" : ""
        ]
          .filter(Boolean)
          .join(" ")}
        ref={rowsRootRef}
        onScroll={shouldVirtualizeRows ? updateVirtualMetrics : undefined}
      >
        {shouldVirtualizeRows ? (
          <div
            className="market-list__virtual-spacer"
            style={{ height: virtualWindow.totalHeight }}
          >
            {virtualWindow.renderedSymbols.map((symbol, offset) =>
              renderRow(symbol, {
                transform: `translate3d(0, ${(virtualWindow.startIndex + offset) * VIRTUAL_ROW_HEIGHT}px, 0)`
              })
            )}
          </div>
        ) : (
          virtualWindow.renderedSymbols.map((symbol) => renderRow(symbol))
        )}
      </div>
    </section>
  );
}

const VISIBLE_SYMBOL_FALLBACK_COUNT = 20;
const VIRTUALIZATION_THRESHOLD = 120;
const VIRTUAL_ROW_HEIGHT = 82;
const VIRTUAL_OVERSCAN_ROWS = 8;
const DEFAULT_VIRTUAL_VIEWPORT_HEIGHT = 720;

type VirtualMetrics = {
  scrollTop: number;
  viewportHeight: number;
};

type VirtualWindow = {
  renderedSymbols: MarketSymbol[];
  startIndex: number;
  totalHeight: number;
};

function resolveVirtualWindow(
  symbols: MarketSymbol[],
  shouldVirtualize: boolean,
  metrics: VirtualMetrics
): VirtualWindow {
  if (!shouldVirtualize) {
    return {
      renderedSymbols: symbols,
      startIndex: 0,
      totalHeight: symbols.length * VIRTUAL_ROW_HEIGHT
    };
  }

  const viewportHeight = Math.max(metrics.viewportHeight, 1);
  const visibleRows = Math.ceil(viewportHeight / VIRTUAL_ROW_HEIGHT);
  const startIndex = Math.max(
    0,
    Math.floor(metrics.scrollTop / VIRTUAL_ROW_HEIGHT) - VIRTUAL_OVERSCAN_ROWS
  );
  const endIndex = Math.min(
    symbols.length,
    startIndex + visibleRows + VIRTUAL_OVERSCAN_ROWS * 2
  );

  return {
    renderedSymbols: symbols.slice(startIndex, endIndex),
    startIndex,
    totalHeight: symbols.length * VIRTUAL_ROW_HEIGHT
  };
}

function resolveActiveGroup(
  groups: MarketSymbolGroup[],
  activeGroupKey: string | null
): MarketSymbolGroup | undefined {
  if (!groups.length) {
    return undefined;
  }

  return groups.find((group) => group.key === activeGroupKey) ?? groups[0];
}

function formatSymbolDetails(symbol: MarketSymbol): string {
  const details = [
    formatMarketGroup(symbol),
    formatCurrencyPair(symbol),
    symbol.maxLeverage ? `${symbol.maxLeverage}x` : undefined
  ].filter(Boolean);

  return details.join(" · ");
}

function formatCurrencyPair(symbol: MarketSymbol): string | undefined {
  if (!symbol.baseCurrency && !symbol.quoteCurrency) {
    return undefined;
  }

  return [symbol.baseCurrency, symbol.quoteCurrency].filter(Boolean).join("/");
}

function sameStringList(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

/**
 * 行情 WebSocket 连接状态对应的颜色 + 文本 hint。
 * - open:           lime 满色 + 持续脉冲（实时正在推送）
 * - connecting:     cyan 半透明 + 慢速脉冲（建连中）
 * - reconnecting:   risk 橙 + 闪烁脉冲（断了在重连）
 * - idle / closed:  subtle 灰（未启动 / 已关闭）
 * - error / auth_expired: short 红（异常态）
 */
function describeConnection(state: MarketConnectionState): { state: string; label: string } {
  const map: Record<MarketConnectionState, { state: string; label: string }> = {
    idle: { state: "idle", label: "等待行情" },
    connecting: { state: "connecting", label: "行情连接中" },
    open: { state: "open", label: "行情已连接" },
    reconnecting: { state: "reconnecting", label: "行情重连中" },
    closed: { state: "closed", label: "行情已关闭" },
    auth_expired: { state: "error", label: "登录已过期" },
    error: { state: "error", label: "行情异常" },
  };
  return map[state];
}
