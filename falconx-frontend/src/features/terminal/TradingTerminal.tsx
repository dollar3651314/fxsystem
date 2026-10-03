import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useAuthStore } from "../auth/authStore";
import {
  isKlineTimeframe,
  type ChartTimeframe
} from "../market/chartTimeframes";
import { getFeaturedSymbols, getKlines, getQuoteHistory, getSymbols } from "../market/marketApi";
import { formatPrice } from "../market/marketFormat";
import { MarketChart } from "../market/MarketChart";
import { MarketTicker } from "../market/MarketTicker";
import { resolveTickerSymbols } from "../market/marketTicker";
import { MarketWatchlist } from "../market/MarketWatchlist";
import {
  groupMarketSymbols,
  resolveActiveMarketGroup,
  resolveMarketGroupSymbol,
  resolvePreferredMarketSymbol
} from "../market/marketSymbolGroups";
import { useMarketStore } from "../market/marketStore";
import { useMarketSocket } from "../market/useMarketSocket";
import { OrderTicket } from "../trading/OrderTicket";
import { TradingTabs, type TabKey as TradingTabKey } from "../trading/TradingTabs";
import {
  useTradingSocket,
  type AccountBalanceChangedEvent,
  type AccountStateUpdate,
  type MarginAddedEvent,
  type NotificationCreatedEvent,
  type OrderFilledEvent,
  type PositionClosedEvent,
  type PositionPnlUpdate,
  type PriceAlertTriggered,
  type UserPositionSummaryUpdate,
} from "../trading/useTradingSocket";
import { ProfilePanel } from "../profile/ProfilePanel";
import { KycSubmitDrawer } from "../kyc/KycSubmitDrawer";
import { getLatestKyc } from "../kyc/kycApi";
import { WithdrawDrawer } from "../withdraw/WithdrawDrawer";
import { WalletPage } from "../wallet/WalletPage";
import { SettingsPage } from "../settings/SettingsPage";
import { ActivityPage } from "../activity/ActivityPage";
import { ArrowDown, ArrowUp, LayoutDashboard, BarChart3, ListChecks, WalletCards, Settings } from "lucide-react";
import { TerminalNav, type TerminalView } from "./TerminalNav";
import { MobileShell } from "./MobileShell";
import { TerminalTopbar } from "./TerminalTopbar";
import { OrderStatusBoard } from "../trading/OrderStatusBoard";
import { DashboardPage } from "../dashboard/DashboardPage";
import { AUTH_EXPIRED_MESSAGE } from "../../lib/authEvents";
import { useBreakpoint } from "../../lib/responsive";
import { formatSignedPnl } from "../../lib/precision";
import { OrderTicketFab, Sheet, type BottomNavItem } from "../../components/mobile";
import { TerminalDrawer } from "./TerminalDrawer";
import { resolveTerminalViewFromHash, terminalViewToHash } from "./terminalRouting";

const MOBILE_NAV_ITEMS: BottomNavItem<TerminalView>[] = [
  { key: "dashboard", label: "首页", icon: <LayoutDashboard size={20} strokeWidth={1.8} /> },
  { key: "market", label: "行情", icon: <BarChart3 size={20} strokeWidth={1.8} /> },
  { key: "activity", label: "活动", icon: <ListChecks size={20} strokeWidth={1.8} /> },
  { key: "wallet", label: "钱包", icon: <WalletCards size={20} strokeWidth={1.8} /> },
  { key: "settings", label: "设置", icon: <Settings size={20} strokeWidth={1.8} /> },
];

interface ToastEvent {
  kind: "ok" | "err";
  text: string;
  level?: "default" | "critical";
}

function dispatchToast(kind: ToastEvent["kind"], text: string, level: ToastEvent["level"] = "default") {
  window.dispatchEvent(new CustomEvent("falconx:toast", { detail: { kind, text, level } }));
}

function formatNumber(value: string | number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  const num = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(num)) return String(value);
  return Math.abs(num) >= 1 ? num.toFixed(2) : num.toFixed(4);
}

export function TradingTerminal() {
  const { isMobile } = useBreakpoint();
  const session = useAuthStore((state) => state.session);
  const clearSession = useAuthStore((state) => state.clearSession);
  const queryClient = useQueryClient();
  const [profileOpen, setProfileOpen] = useState(false);
  const [kycOpen, setKycOpen] = useState(false);
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [ticketOpen, setTicketOpen] = useState(false);
  const [currentView, setCurrentView] = useState<TerminalView>(() =>
    typeof window === "undefined"
      ? "dashboard"
      : resolveTerminalViewFromHash(window.location.hash) ?? "dashboard"
  );
  // 首页 stat 卡点击时强制切到指定 tab（用 nonce 让相同 key 也能 re-trigger）
  const [forceTradingTab, setForceTradingTab] = useState<{ key: TradingTabKey; nonce: number } | null>(null);
  const navigateToMarketTab = useCallback((tab?: TradingTabKey) => {
    setCurrentView("market");
    if (tab) {
      setForceTradingTab({ key: tab, nonce: Date.now() });
      // 2026-05-28 v2 修：dashboard→market 切视图后 chart canvas 需要时间 layout
      // （min-height: clamp + ResizeObserver），rAF×2 不够，会在 panel 还在 ~300px 时
      // 就 scrollIntoView，结果 §04 panel 看起来"没动"。setTimeout 250ms 保证 layout 稳定。
      const tryScroll = (delay: number) =>
        window.setTimeout(() => {
          const el = document.querySelector(".fx-trading-tabs");
          if (el) {
            el.scrollIntoView({ behavior: "smooth", block: "start" });
          }
        }, delay);
      tryScroll(80);   // 早一次：rAF 已就位时尝试
      tryScroll(280);  // 晚一次：chart layout 真正稳定后兜底
    }
  }, []);

  // §02 chart 头部右上角的 §04 跳转 chip — 点击 (1) 强制 activate 对应 trading tab；
  // (2) 在下一帧 smooth-scroll 到 .fx-trading-tabs。桌面端 .terminal-center 是滚动容器，
  // 移动端则是窗口在滚；scrollIntoView 都通用。
  const handleJumpToOrdersTab = useCallback((tab: TradingTabKey) => {
    setForceTradingTab({ key: tab, nonce: Date.now() });
    requestAnimationFrame(() => {
      const tabsEl = document.querySelector(".fx-trading-tabs");
      tabsEl?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }, []);
  // 4 个常用 tab 做快捷入口，剩余 历史/成交 仍可在面板内自由切换
  const jumpChips = (
    <div className="fx-jump-chips" role="group" aria-label="跳转到交易明细">
      <span className="fx-jump-chips__label">§04 直达</span>
      {([
        { key: "positions", label: "持仓" },
        { key: "pending", label: "挂单" },
        { key: "orders", label: "订单" },
        { key: "alerts", label: "告警" },
      ] as { key: TradingTabKey; label: string }[]).map((item) => (
        <button
          key={item.key}
          type="button"
          className="fx-jump-chip"
          onClick={() => handleJumpToOrdersTab(item.key)}
          title={`跳转下方 §04 ${item.label}`}
        >
          {item.label}
          <ArrowDown
            size={12}
            strokeWidth={2.4}
            aria-hidden="true"
            className="fx-jump-chip__arrow"
          />
        </button>
      ))}
    </div>
  );

  // 反向跳转：§04 → §02 K 线（回到顶部）。chart 占大屏后用户可能滚到下方 §04 就找不到 K 线，
  // 给一个明确的「返回 K 线 ↑」chip 放在 §04 panel head 右上。
  // 桌面端滚 .terminal-center 这个滚动容器；移动端 .market-chart 直接 scrollIntoView 让 page scroll 处理。
  const handleJumpBackToChart = useCallback(() => {
    const center = document.querySelector(".terminal-center") as HTMLElement | null;
    if (center) {
      center.scrollTo({ top: 0, behavior: "smooth" });
    } else {
      document
        .querySelector(".market-chart")
        ?.scrollIntoView({ behavior: "smooth", block: "start" });
    }
  }, []);
  const returnToChartChip = (
    <div className="fx-jump-chips" role="group" aria-label="返回 K 线">
      <button
        type="button"
        className="fx-jump-chip"
        onClick={handleJumpBackToChart}
        title="返回上方 §02 行情图表"
      >
        返回 K 线
        <ArrowUp
          size={12}
          strokeWidth={2.4}
          aria-hidden="true"
          className="fx-jump-chip__arrow fx-jump-chip__arrow--up"
        />
      </button>
    </div>
  );

  // STAGE-2-REALTIME-DATA：trading socket 上提到 TradingTerminal 后，跨视图切换 WS 不重连，
  // pnlMap 跨 dashboard / market 视图共享，两端表格都能实时刷新。
  const [toast, setToast] = useState<ToastEvent | null>(null);
  const pnlBufferRef = useRef<Map<string, PositionPnlUpdate>>(new Map());
  const [pnlMap, setPnlMap] = useState<Map<string, PositionPnlUpdate>>(new Map());
  const accountToastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  /**
   * 后端聚合并节流后推送的「总未实现盈亏」（user.position.summary, 500ms/user）。
   * dashboard 和 market 持仓 tab 顶部直接显示这个数，无需各自累加 pnlMap。
   * null = 还没收到任何 WS push，UI 应该 fallback REST summary 接口的初值。
   */
  const [totalUnrealizedPnl, setTotalUnrealizedPnl] = useState<string | null>(null);
  const handlePositionSummary = useCallback((update: UserPositionSummaryUpdate) => {
    setTotalUnrealizedPnl(update.totalUnrealizedPnl);
  }, []);

  /**
   * STAGE-14E1：账户级实时态（WS account.update/snapshot）。本 task 仅持有 state，
   * MarginLevel 浮窗 / MarginMode toggle UI 留 Task5-6。
   * accountMarginMode 是账户级实际生效模式，区别于本地下单偏好 preferencesStore.defaultMarginMode。
   */
  const [accountEquity, setAccountEquity] = useState<string | null>(null);
  const [marginLevel, setMarginLevel] = useState<string | null>(null);
  const [marginLevelStatus, setMarginLevelStatus] = useState<string | null>(null);
  const [accountMarginMode, setAccountMarginMode] = useState<string | null>(null);
  const handleAccountUpdate = useCallback((event: AccountStateUpdate) => {
    setAccountEquity(event.equity);
    setMarginLevel(event.marginLevel);
    setMarginLevelStatus(event.marginLevelStatus);
    setAccountMarginMode(event.accountMarginMode);
  }, []);
  // accountEquity 由 §00 常驻订单数据看板（OrderStatusBoard）消费

  /**
   * STAGE-14E1 Task6：marginLevelStatus 从非危态跃迁到 MARGIN_CALL / STOP_OUT 时弹 critical toast。
   * useRef 记录上次 status，仅状态跃迁时弹一次（避免每条 account.update 都弹），
   * 退回 HEALTHY 后再次进入预警/强平会重新弹。
   */
  const prevMarginStatusRef = useRef<string | null>(null);
  useEffect(() => {
    const prev = prevMarginStatusRef.current;
    prevMarginStatusRef.current = marginLevelStatus;
    if (!marginLevelStatus || marginLevelStatus === prev) return;
    const isCritical = marginLevelStatus === "MARGIN_CALL" || marginLevelStatus === "STOP_OUT";
    const wasCritical = prev === "MARGIN_CALL" || prev === "STOP_OUT";
    // 仅「非危→危」或「危态之间升级（MARGIN_CALL→STOP_OUT）」时弹
    if (!isCritical) return;
    if (wasCritical && !(prev === "MARGIN_CALL" && marginLevelStatus === "STOP_OUT")) return;
    const text =
      marginLevelStatus === "STOP_OUT"
        ? `保证金已触及强平线（${marginLevel ?? "—"}%），系统将强制平仓`
        : `保证金水平预警（${marginLevel ?? "—"}%），请尽快追加保证金或减仓`;
    dispatchToast("err", text, "critical");
  }, [marginLevelStatus, marginLevel]);

  // 任一 trading lifecycle 事件后让 trading + dashboard 缓存都过期
  const refetchTradingAndDashboard = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ["trading"] });
    void queryClient.invalidateQueries({ queryKey: ["dashboard"] });
  }, [queryClient]);

  const handlePositionPnl = useCallback((update: PositionPnlUpdate) => {
    pnlBufferRef.current.set(update.positionId, update);
  }, []);

  // 100ms 把 buffer 提到 state，触发 PositionsTable / Dashboard 重渲染
  // 2026-05-26 Sprint 5 C2：tab hidden（用户切走 / 最小化）时暂停 setPnlMap，
  // 避免 React 在不可见 tab 持续 reconcile。pnlBufferRef 仍累积 ws 推送，
  // tab 重新可见时一次性 flush（用户切回看到最新状态）。
  useEffect(() => {
    let timer: ReturnType<typeof setInterval> | null = null;
    const flushPnlBuffer = () => {
      if (pnlBufferRef.current.size === 0) return;
      const snapshot = new Map(pnlBufferRef.current);
      setPnlMap((prev) => {
        const next = new Map(prev);
        snapshot.forEach((value, key) => next.set(key, value));
        return next;
      });
    };
    const startTimer = () => {
      if (timer !== null) return;
      timer = setInterval(flushPnlBuffer, 100);
    };
    const stopTimer = () => {
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        flushPnlBuffer();  // 切回先 flush 一次，看到最新
        startTimer();
      } else {
        stopTimer();
      }
    };

    if (document.visibilityState === "visible") {
      startTimer();
    }
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      stopTimer();
    };
  }, []);

  const handlePositionClosed = useCallback((event?: PositionClosedEvent) => {
    pnlBufferRef.current.clear();
    setPnlMap(new Map());
    refetchTradingAndDashboard();
    if (!event) return;
    const sideLabel = event.side === "BUY" || event.side === "LONG" ? "多" : "空";
    if (event.status === "LIQUIDATED") {
      const reason = event.liquidationReason ? `（${event.liquidationReason}）` : "";
      dispatchToast("err", `⚠️ ${event.symbol} ${sideLabel} 被强平${reason}`, "critical");
    } else {
      const pnl = event.realizedPnl;
      const pnlLabel = pnl != null
        ? `已实现 ${formatSignedPnl(pnl)} USDT`
        : "已平仓";
      const priceLabel = event.closePrice ? ` @ ${event.closePrice}` : "";
      dispatchToast(Number(pnl) >= 0 ? "ok" : "err", `${event.symbol} ${sideLabel} ${pnlLabel}${priceLabel}`, "critical");
    }
  }, [refetchTradingAndDashboard]);

  const handleOrderFilled = useCallback((event?: OrderFilledEvent) => {
    refetchTradingAndDashboard();
    if (!event || !event.orderNo) return;
    const sideLabel = event.side === "BUY" ? "买入" : event.side === "SELL" ? "卖出" : event.side;
    const qtyLabel = event.filledQty ? ` ${event.filledQty}` : "";
    const priceLabel = event.avgPrice ? ` @ ${event.avgPrice}` : "";
    dispatchToast("ok", `✓ ${event.symbol} ${sideLabel}${qtyLabel} 已成交${priceLabel}`);
  }, [refetchTradingAndDashboard]);

  const handleMarginAdded = useCallback((event: MarginAddedEvent) => {
    refetchTradingAndDashboard();
    if (!event.amount) return;
    dispatchToast("ok", `${event.symbol} 已补充保证金 ${formatNumber(event.amount)} USDT`);
  }, [refetchTradingAndDashboard]);

  const handleNotificationCreated = useCallback((event: NotificationCreatedEvent) => {
    // 通知中心刷未读 + 列表；dashboard 通知卡也同步刷
    window.dispatchEvent(new CustomEvent("falconx:notification:created", { detail: event }));
    void queryClient.invalidateQueries({ queryKey: ["dashboard", "notifications"] });
    void queryClient.invalidateQueries({ queryKey: ["trading", "notifications"] });
    const kind = event.level === "CRITICAL" ? "err" : "ok";
    const level = event.level === "CRITICAL" ? "critical" : "default";
    dispatchToast(kind, event.title, level);
  }, [queryClient]);

  const handleAccountBalanceChanged = useCallback((event: AccountBalanceChangedEvent) => {
    void queryClient.invalidateQueries({ queryKey: ["dashboard", "account"] });
    void queryClient.invalidateQueries({ queryKey: ["trading", "account"] });
    if (accountToastTimerRef.current) clearTimeout(accountToastTimerRef.current);
    accountToastTimerRef.current = setTimeout(() => {
      if (event.delta != null) {
        const num = Number(event.delta);
        if (Number.isFinite(num) && Math.abs(num) >= 0.01) {
          const sign = num >= 0 ? "+" : "";
          dispatchToast("ok", `账户余额 ${sign}${formatNumber(event.delta)} ${event.currency}`);
        }
      }
    }, 800);
  }, [queryClient]);

  const handlePriceAlertTriggered = useCallback((event: PriceAlertTriggered) => {
    const dirLabel = event.direction === "ABOVE" ? "向上穿透" : "向下穿透";
    const exhaustedLabel = event.exhausted ? "（已用完 3 次）" : `（剩余 ${event.remainingTriggers} 次）`;
    const text = `🔔 ${event.symbol} ${dirLabel} ${event.targetPrice} → 现价 ${event.triggeredPrice}${exhaustedLabel}${event.note ? "：" + event.note : ""}`;
    window.dispatchEvent(new CustomEvent("falconx:toast", { detail: { kind: "ok", text } }));
    void queryClient.invalidateQueries({ queryKey: ["trading", "price-alerts"] });
    void queryClient.invalidateQueries({ queryKey: ["dashboard", "price-alerts"] });
  }, [queryClient]);

  // toast 事件总线 + 自动隐藏
  useEffect(() => {
    const handler = (event: Event) => {
      if (event instanceof CustomEvent && event.detail) {
        setToast(event.detail as ToastEvent);
      }
    };
    window.addEventListener("falconx:toast", handler);
    return () => window.removeEventListener("falconx:toast", handler);
  }, []);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 4_000);
    return () => clearTimeout(timer);
  }, [toast]);
  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }

    const nextHash = terminalViewToHash(currentView);
    if (window.location.hash !== nextHash) {
      window.history.replaceState({ terminalView: currentView }, "", nextHash);
    }
  }, [currentView]);
  const selectedSymbol = useMarketStore((state) => state.selectedSymbol);
  const setSelectedSymbol = useMarketStore((state) => state.setSelectedSymbol);
  const connectionState = useMarketStore((state) => state.connectionState);
  const quotes = useMarketStore((state) => state.quotes);
  const quoteHistory = useMarketStore((state) => state.quoteHistory);
  const klines = useMarketStore((state) => state.klines);
  const accessToken = session?.accessToken;
  const [timeframe, setTimeframe] = useState<ChartTimeframe>("1m");
  const [activeMarketGroup, setActiveMarketGroup] = useState<string | null>(null);
  const [visiblePriceSymbols, setVisiblePriceSymbols] = useState<string[]>([]);
  const selectedKlineInterval = isKlineTimeframe(timeframe) ? timeframe : null;
  const chartKlineInterval = selectedKlineInterval ?? "1m";
  const isTickTimeframe = timeframe === "tick";

  const symbolsQuery = useQuery({
    queryKey: ["market", "symbols", accessToken],
    queryFn: () => getSymbols(accessToken ?? ""),
    enabled: Boolean(accessToken),
    staleTime: 30_000
  });

  const kycLatestQuery = useQuery({
    queryKey: ["identity", "kyc", "latest"],
    queryFn: () => (accessToken ? getLatestKyc(accessToken) : Promise.resolve(null)),
    enabled: Boolean(accessToken),
    staleTime: 60_000,
  });
  // 派生 KYC 显示状态：kyc_level ≥ 1 → 已认证（权限源，最稳口径）；
  // kyc_level=0 时再看 submission status（审核工作流）；都没有则 NONE
  const kycStatus: "NONE" | "PENDING" | "APPROVED" | "REJECTED" = (() => {
    const data = kycLatestQuery.data;
    if (!data) return "NONE";
    if (data.currentKycLevel >= 1) return "APPROVED";
    return data.status ?? "NONE";
  })();

  const symbolGroups = useMemo(
    () => groupMarketSymbols(symbolsQuery.data ?? []),
    [symbolsQuery.data]
  );
  // 顶栏跑马灯热门品种：优先管理端配置（market /symbols/featured），未配置时回退前端默认偏好。
  // 配置项须命中真实 symbols（owner）才订阅，避免管理端残留已下架 symbol 导致订阅无效频道。
  const featuredQuery = useQuery({
    queryKey: ["market", "featured", accessToken],
    queryFn: () => getFeaturedSymbols(accessToken ?? ""),
    enabled: Boolean(accessToken),
    staleTime: 60_000
  });
  const tickerSymbols = useMemo(() => {
    const all = symbolsQuery.data ?? [];
    const configured = (featuredQuery.data ?? []).filter((sym) =>
      all.some((s) => s.symbol === sym)
    );
    return configured.length ? configured : resolveTickerSymbols(all, 8);
  }, [featuredQuery.data, symbolsQuery.data]);
  const effectiveActiveMarketGroup = useMemo(
    () => resolveActiveMarketGroup(symbolGroups, activeMarketGroup)?.key ?? null,
    [activeMarketGroup, symbolGroups]
  );
  useEffect(() => {
    if (!symbolGroups.length) {
      return;
    }

    const nextSelectedSymbol = resolveMarketGroupSymbol(
      symbolGroups,
      effectiveActiveMarketGroup,
      selectedSymbol
    );
    if (!nextSelectedSymbol || nextSelectedSymbol === selectedSymbol) {
      return;
    }

    setSelectedSymbol(nextSelectedSymbol);
  }, [
    effectiveActiveMarketGroup,
    selectedSymbol,
    setSelectedSymbol,
    symbolGroups
  ]);

  const selectedSymbolMeta = symbolsQuery.data?.find(
    (symbol) => symbol.symbol === selectedSymbol
  );
  const klineHistoryQuery = useQuery({
    queryKey: ["market", "klines", accessToken, selectedSymbol, chartKlineInterval],
    queryFn: () =>
      getKlines(accessToken ?? "", selectedSymbol ?? "", chartKlineInterval, 200),
    enabled: Boolean(accessToken && selectedSymbol && selectedKlineInterval),
    placeholderData: (previousData, previousQuery) =>
      previousQuery?.queryKey[3] === selectedSymbol &&
      previousQuery.queryKey[4] === chartKlineInterval
        ? previousData
        : undefined,
    staleTime: 30_000
  });
  const quoteHistoryQuery = useQuery({
    queryKey: ["market", "quote-history", accessToken, selectedSymbol],
    queryFn: () => getQuoteHistory(accessToken ?? "", selectedSymbol ?? "", 600),
    enabled: Boolean(accessToken && selectedSymbol && isTickTimeframe),
    placeholderData: (previousData, previousQuery) =>
      previousQuery?.queryKey[3] === selectedSymbol ? previousData : undefined,
    staleTime: 10_000
  });

  const handleMarketAuthExpired = useCallback(() => {
    clearSession(AUTH_EXPIRED_MESSAGE);
  }, [clearSession]);

  const handleMarketGroupChange = useCallback(
    (groupKey: string) => {
      setVisiblePriceSymbols([]);
      setActiveMarketGroup(groupKey);
      const nextGroup = symbolGroups.find((group) => group.key === groupKey);
      if (!nextGroup?.symbols.length) {
        return;
      }
      if (nextGroup.symbols.some((symbol) => symbol.symbol === selectedSymbol)) {
        return;
      }

      const nextSymbol = resolvePreferredMarketSymbol(nextGroup);
      if (nextSymbol) {
        setSelectedSymbol(nextSymbol.symbol);
      }
    },
    [selectedSymbol, setSelectedSymbol, symbolGroups]
  );

  const handleVisibleSymbolsChange = useCallback((symbols: string[]) => {
    setVisiblePriceSymbols(symbols);
  }, []);

  // watchlist 可见项 + 跑马灯热门品种一起订阅 price.tick（hook 内 Set 去重）
  const watchedPriceSymbols = useMemo(
    () => [...new Set([...visiblePriceSymbols, ...tickerSymbols])],
    [visiblePriceSymbols, tickerSymbols]
  );
  useMarketSocket({
    token: accessToken,
    watchedSymbols: watchedPriceSymbols,
    selectedSymbol,
    klineInterval: chartKlineInterval,
    onAuthExpired: handleMarketAuthExpired
  });

  // trading socket：跨视图常驻；onPositionRejected 也走 toast 但不需要回调下面引用，
  // 内联即可避免再造一个 useCallback
  const tradingSocketState = useTradingSocket(accessToken ?? null, {
    onOrderFilled: handleOrderFilled,
    onOrderRejected: (reason) => {
      refetchTradingAndDashboard();
      const reasonLabel = reason ? `：${reason}` : "";
      dispatchToast("err", `订单被风控拒绝${reasonLabel}`, "critical");
    },
    onPositionClosed: handlePositionClosed,
    onPositionPnl: handlePositionPnl,
    onPriceAlertTriggered: handlePriceAlertTriggered,
    onMarginAdded: handleMarginAdded,
    onAccountBalanceChanged: handleAccountBalanceChanged,
    onAccountUpdate: handleAccountUpdate,
    onNotificationCreated: handleNotificationCreated,
    onPositionSummary: handlePositionSummary,
  });

  const selectedQuote = selectedSymbol ? quotes[selectedSymbol] : undefined;
  const selectedLiveQuoteHistory = selectedSymbol ? (quoteHistory[selectedSymbol] ?? []) : [];
  const selectedQuoteHistory = isTickTimeframe
    ? [...(quoteHistoryQuery.data ?? []), ...selectedLiveQuoteHistory]
    : selectedLiveQuoteHistory;

  return (
    <MobileShell
      activeKey={currentView}
      onSelectKey={setCurrentView}
      navItems={MOBILE_NAV_ITEMS}
      drawerContent={({ close }) => (
        <TerminalDrawer
          onOpenProfile={() => {
            close();
            setProfileOpen(true);
          }}
          onOpenKyc={() => {
            close();
            setKycOpen(true);
          }}
          onNavigateToWallet={() => {
            close();
            setCurrentView("wallet");
          }}
        />
      )}
    >
    <main className={`trading-terminal trading-terminal--${currentView}`}>
      {!isMobile && <TerminalNav activeKey={currentView} onSelectKey={setCurrentView} />}
      <section className={`terminal-workspace terminal-workspace--${currentView}`}>
        {/* §00 订单数据看板并入顶栏（与通知/KYC 同行），不再单占一行，给下方让出空间 */}
        <TerminalTopbar
          onOpenProfile={() => setProfileOpen(true)}
          onOpenKyc={() => setKycOpen(true)}
          onNavigateToWallet={() => setCurrentView("wallet")}
          kycStatus={kycStatus}
          orderBoard={
            <OrderStatusBoard
              totalUnrealizedPnl={totalUnrealizedPnl}
              accountEquity={accountEquity}
              marginLevel={marginLevel}
              marginLevelStatus={marginLevelStatus}
              accountMarginMode={accountMarginMode}
            />
          }
          ticker={
            <MarketTicker symbols={tickerSymbols} symbolsMeta={symbolsQuery.data} />
          }
        />
        {currentView === "dashboard" ? (
          <DashboardPage
            onNavigateToMarket={navigateToMarketTab}
            pnlMap={pnlMap}
            serverTotalUnrealizedPnl={totalUnrealizedPnl}
            marginLevel={marginLevel}
            marginLevelStatus={marginLevelStatus}
            accountMarginMode={accountMarginMode}
          />
        ) : currentView === "wallet" ? (
          <WalletPage onOpenWithdraw={() => setWithdrawOpen(true)} />
        ) : currentView === "settings" ? (
          <SettingsPage
            onOpenProfile={() => setProfileOpen(true)}
            onOpenKyc={() => setKycOpen(true)}
          />
        ) : currentView === "activity" ? (
          <ActivityPage />
        ) : (
          <>
            {isMobile ? (
              <>
                {/* 2026-05-28 二轮 H5：chart 先于 watchlist 渲染，让 K 线占首屏；
                  * watchlist 折到 chart 下面、被压缩为 ~240px 高的紧凑滚动条；
                  * §04 在 watchlist 之下，仍然可滚到。chips 顶部直达 §04，回头有「返回 K 线」反向 chip。 */}
                <MarketChart
                  symbol={selectedSymbolMeta}
                  quote={selectedQuote}
                  quoteHistory={selectedQuoteHistory}
                  klineHistory={klineHistoryQuery.data ?? []}
                  liveKlines={
                    selectedSymbol
                      ? (klines[selectedSymbol]?.[chartKlineInterval] ?? [])
                      : []
                  }
                  selectedSymbol={selectedSymbol}
                  connectionState={connectionState}
                  isLoading={
                    symbolsQuery.isLoading ||
                    Boolean(klineHistoryQuery.isLoading && !klineHistoryQuery.data) ||
                    Boolean(quoteHistoryQuery.isLoading && !quoteHistoryQuery.data)
                  }
                  timeframe={timeframe}
                  onTimeframeChange={setTimeframe}
                  headerExtras={jumpChips}
                />
                <MarketWatchlist
                  groups={symbolsQuery.data ? symbolGroups : undefined}
                  activeGroupKey={effectiveActiveMarketGroup}
                  quotes={quotes}
                  selectedSymbol={selectedSymbol}
                  connectionState={connectionState}
                  isLoading={symbolsQuery.isLoading}
                  isError={symbolsQuery.isError}
                  errorMessage={symbolsQuery.error instanceof Error ? symbolsQuery.error.message : undefined}
                  onRetry={() => void symbolsQuery.refetch()}
                  onSelectSymbol={setSelectedSymbol}
                  onSelectGroup={handleMarketGroupChange}
                  onVisibleSymbolsChange={handleVisibleSymbolsChange}
                />
                <OrderTicketFab
                  symbol={selectedSymbol}
                  priceLabel={formatPrice(
                    selectedQuote?.ask ?? selectedQuote?.bid,
                    selectedSymbolMeta?.pricePrecision
                  )}
                  onClick={() => setTicketOpen(true)}
                />
                <TradingTabs
                  pnlMap={pnlMap}
                  socketState={tradingSocketState}
                  forceActiveTab={forceTradingTab}
                  serverTotalUnrealizedPnl={totalUnrealizedPnl}
                  headerExtras={returnToChartChip}
                />
                <Sheet
                  open={ticketOpen}
                  onClose={() => setTicketOpen(false)}
                  title={selectedSymbol ? `${selectedSymbol} 下单` : "下单"}
                >
                  <OrderTicket symbolMeta={selectedSymbolMeta ?? null} />
                </Sheet>
              </>
            ) : (
              <>
                <MarketWatchlist
                  groups={symbolsQuery.data ? symbolGroups : undefined}
                  activeGroupKey={effectiveActiveMarketGroup}
                  quotes={quotes}
                  selectedSymbol={selectedSymbol}
                  connectionState={connectionState}
                  isLoading={symbolsQuery.isLoading}
                  isError={symbolsQuery.isError}
                  errorMessage={symbolsQuery.error instanceof Error ? symbolsQuery.error.message : undefined}
                  onRetry={() => void symbolsQuery.refetch()}
                  onSelectSymbol={setSelectedSymbol}
                  onSelectGroup={handleMarketGroupChange}
                  onVisibleSymbolsChange={handleVisibleSymbolsChange}
                />
                <div className="terminal-center">
                  <MarketChart
                    symbol={selectedSymbolMeta}
                    quote={selectedQuote}
                    quoteHistory={selectedQuoteHistory}
                    klineHistory={klineHistoryQuery.data ?? []}
                    liveKlines={
                      selectedSymbol
                        ? (klines[selectedSymbol]?.[chartKlineInterval] ?? [])
                        : []
                    }
                    selectedSymbol={selectedSymbol}
                    connectionState={connectionState}
                    isLoading={
                      symbolsQuery.isLoading ||
                      Boolean(klineHistoryQuery.isLoading && !klineHistoryQuery.data) ||
                      Boolean(quoteHistoryQuery.isLoading && !quoteHistoryQuery.data)
                    }
                    timeframe={timeframe}
                    onTimeframeChange={setTimeframe}
                    headerExtras={jumpChips}
                  />
                  <TradingTabs
                    pnlMap={pnlMap}
                    socketState={tradingSocketState}
                    forceActiveTab={forceTradingTab}
                    serverTotalUnrealizedPnl={totalUnrealizedPnl}
                    headerExtras={returnToChartChip}
                  />
                </div>
                <OrderTicket symbolMeta={selectedSymbolMeta ?? null} />
              </>
            )}
          </>
        )}
      </section>
      <ProfilePanel
        open={profileOpen}
        onClose={() => setProfileOpen(false)}
        currentKycLevel={kycLatestQuery.data?.currentKycLevel ?? 0}
      />
      <KycSubmitDrawer open={kycOpen} onClose={() => setKycOpen(false)} />
      <WithdrawDrawer
        open={withdrawOpen}
        onClose={() => setWithdrawOpen(false)}
        onOpenKyc={() => {
          setWithdrawOpen(false);
          setKycOpen(true);
        }}
      />
      {toast && (
        <div
          className={`fx-floating-toast ${toast.kind} ${toast.level === "critical" ? "fx-floating-toast--center" : ""}`}
          role={toast.kind === "err" ? "alert" : "status"}
        >
          {toast.text}
        </div>
      )}
    </main>
    </MobileShell>
  );
}
