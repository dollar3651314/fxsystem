import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useMemo } from "react";
import { useAuthStore } from "../auth/authStore";
import {
  getAccount,
  getAccountSwapSummary,
  getPositionSummary,
  listNotifications,
  listPendingOrders,
  listPositions,
  listPriceAlerts,
  listTrades,
} from "../trading/tradingApi";
import type {
  NotificationItem,
  PendingOrderItem,
  PositionItem,
  TradeItem,
} from "../trading/tradingTypes";
import type { PositionPnlUpdate } from "../trading/useTradingSocket";
import type { TabKey } from "../trading/TradingTabs";
import { MarginLevelIndicator } from "../trading/MarginLevelIndicator";
import { ACCOUNT_CURRENCY, pnlColorClass, shouldShowQuoteRow } from "../trading/pnlDisplay";
import { formatMoney, formatPnl, formatSignedMoney, formatSignedPnl } from "../../lib/precision";

interface Props {
  /** 跳到「市场」并可选 focus 某个 trading tab（持仓 / 挂单 / 历史 …）。 */
  onNavigateToMarket: (tab?: TabKey) => void;
  /**
   * TradingTerminal 透传的 WS pnlMap，positionId → patch。
   * STAGE-14E1：patch 双币口径 —— markPrice / unrealizedPnlInAccount（账户币 USDT，主）/
   * unrealizedPnlInQuote（报价币，副）/ quoteCurrency。
   */
  pnlMap: Map<string, PositionPnlUpdate>;
  /**
   * 后端聚合并 500ms 节流推送的「总未实现盈亏」（user.position.summary）。
   * 直接显示，避免前端累加 pnlMap 漏算翻页外 / 超 size=100 持仓的精度问题。
   */
  serverTotalUnrealizedPnl: string | null;
  /**
   * STAGE-14E1 Task6：账户级保证金水平百分比（WS account.update，TradingTerminal state 透传）。
   * null = 无持仓 / FX 降级，显示 "—"。
   */
  marginLevel: string | null;
  /** 账户级保证金水平三态（HEALTHY / MARGIN_CALL / STOP_OUT），null = 无数据。 */
  marginLevelStatus: string | null;
  /** 账户级实际生效保证金模式（ISOLATED / CROSS），null = 无数据。 */
  accountMarginMode: string | null;
}

/**
 * 首页 Dashboard：账户 + 持仓 + 最近活动概览。
 *
 * <p>数据完全靠 WS 驱动：
 * - account.update → invalidate ["dashboard","account"]
 * - position.pnl → 直接走 pnlMap 实时 patch（持仓 / 未实现盈亏聚合）
 * - position.closed / order.filled → invalidate ["dashboard","positions"/"trades"/...]
 * - notification.created → invalidate ["dashboard","notifications"]
 * - price-alert.triggered → invalidate ["dashboard","price-alerts"]
 *
 * <p>WS 不覆盖的（用户在另一会话新建挂单 / 价格告警 / 平仓后查看历史）走右上角「刷新」按钮。
 * 全部 query 都用 staleTime: Infinity，关掉 React Query 的自动 refetch / polling。
 */
export function DashboardPage({
  onNavigateToMarket,
  pnlMap,
  serverTotalUnrealizedPnl,
  marginLevel,
  marginLevelStatus,
  accountMarginMode,
}: Props) {
  const session = useAuthStore((s) => s.session);
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? null;
  const enabled = Boolean(token);
  // staleTime: Infinity + refetchOnWindowFocus: false → 完全断掉轮询；只靠 WS handler 或刷新按钮触发 invalidate
  const baseQueryOpts = {
    enabled,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  } as const;

  const accountQuery = useQuery({
    queryKey: ["dashboard", "account"],
    queryFn: () => getAccount(token!),
    ...baseQueryOpts,
  });

  // 账户维度 Swap 累计（近 30 天），与 hero PnL 时间窗一致
  const swapSummaryQuery = useQuery({
    queryKey: ["dashboard", "swap-summary", 30],
    queryFn: () => getAccountSwapSummary(token!, 30),
    enabled,
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });

  const openPositionsQuery = useQuery({
    queryKey: ["dashboard", "positions", "OPEN"],
    queryFn: () => listPositions(token!, 1, 100, "OPEN"),
    ...baseQueryOpts,
  });

  const closedPositionsQuery = useQuery({
    queryKey: ["dashboard", "positions", "CLOSED"],
    queryFn: () => listPositions(token!, 1, 50, "CLOSED,LIQUIDATED"),
    ...baseQueryOpts,
  });

  const pendingOrdersQuery = useQuery({
    queryKey: ["dashboard", "pending-orders"],
    queryFn: () => listPendingOrders(token!, 1, 50),
    ...baseQueryOpts,
  });

  const alertsQuery = useQuery({
    queryKey: ["dashboard", "price-alerts"],
    // status=1 (ACTIVE)
    queryFn: () => listPriceAlerts(token!, 1, undefined, 1, 50),
    ...baseQueryOpts,
  });

  const notificationsQuery = useQuery({
    queryKey: ["dashboard", "notifications"],
    queryFn: () => listNotifications(token!, 1, 5),
    ...baseQueryOpts,
  });

  const tradesQuery = useQuery({
    queryKey: ["dashboard", "trades"],
    queryFn: () => listTrades(token!, 1, 5),
    ...baseQueryOpts,
  });

  // 持仓汇总 REST 初值（mount 拉一次保底；之后由 WS user.position.summary 实时刷）
  const summaryQuery = useQuery({
    queryKey: ["dashboard", "position-summary"],
    queryFn: () => getPositionSummary(token!),
    ...baseQueryOpts,
  });

  // 手动刷新：把所有 dashboard 缓存置 stale，由 useQuery 重新拉
  const handleRefresh = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: ["dashboard"] });
  }, [queryClient]);

  // --- 聚合衍生指标 ---
  // 总未实现盈亏：优先 WS push（跨 symbol 聚合）→ REST summary 初值 → 都没有显示 —
  const liveUnrealized = useMemo(() => {
    if (serverTotalUnrealizedPnl != null) {
      const n = Number(serverTotalUnrealizedPnl);
      if (Number.isFinite(n)) return { value: n, available: true, source: "ws" as const };
    }
    const restValue = summaryQuery.data?.totalUnrealizedPnl;
    if (restValue != null) {
      const n = Number(restValue);
      if (Number.isFinite(n)) return { value: n, available: true, source: "rest" as const };
    }
    return { value: 0, available: false, source: "none" as const };
  }, [serverTotalUnrealizedPnl, summaryQuery.data]);

  // 持仓 mini 表 + 占用保证金合计：基于当前可见 items + pnlMap（仅用于展示，与总 PnL 解耦）
  const positionStats = useMemo(() => {
    const items = openPositionsQuery.data?.items ?? [];
    const enriched = items.map((p) => {
      const patch = pnlMap.get(p.positionId);
      const markPrice = patch?.markPrice ?? p.markPrice;
      // STAGE-14E1 Task7：双币 —— 账户币（USDT，主）+ 报价币（quoteCurrency，副），WS patch 优先
      const upnlStr = patch?.unrealizedPnlInAccount ?? p.unrealizedPnlInAccount;
      const upnlQuoteStr = patch?.unrealizedPnlInQuote ?? p.unrealizedPnlInQuote;
      const quoteCurrency = patch?.quoteCurrency ?? p.quoteCurrency;
      return {
        ...p,
        markPrice,
        unrealizedPnlInAccount: upnlStr,
        unrealizedPnlInQuote: upnlQuoteStr,
        quoteCurrency,
      };
    });
    const totalMargin = items.reduce((sum, p) => {
      const margin = Number(p.margin);
      return Number.isFinite(margin) ? sum + margin : sum;
    }, 0);
    // 2026-05-28：原按 |未实现盈亏| 排序，用户要求改为按开仓时间倒序（与 §04 持仓 tab 一致）
    // TradingPosition 时间字段是 openedAt（不是 createdAt）。
    const top5 = [...enriched].sort((a, b) => {
      const at = a.openedAt ? Date.parse(a.openedAt) : 0;
      const bt = b.openedAt ? Date.parse(b.openedAt) : 0;
      return bt - at;
    }).slice(0, 5);
    return { count: items.length, totalMargin, top5 };
  }, [openPositionsQuery.data, pnlMap]);

  const closedStats = useMemo(() => {
    const items: PositionItem[] = closedPositionsQuery.data?.items ?? [];
    let realized = 0;
    let realizedAvailable = false;
    for (const p of items) {
      const pnl = p.realizedPnl == null ? null : Number(p.realizedPnl);
      if (pnl != null && Number.isFinite(pnl)) {
        realized += pnl;
        realizedAvailable = true;
      }
    }
    return { count: items.length, realized, realizedAvailable };
  }, [closedPositionsQuery.data]);

  const tradeStats = useMemo(() => {
    const items: TradeItem[] = tradesQuery.data?.items ?? [];
    let totalFee = 0;
    for (const t of items) {
      const fee = Number(t.fee);
      if (Number.isFinite(fee)) totalFee += fee;
    }
    return { recent: items, totalFee, count: items.length };
  }, [tradesQuery.data]);

  const pendingOrders: PendingOrderItem[] = pendingOrdersQuery.data?.items ?? [];
  const pendingTop5 = pendingOrders.slice(0, 5);
  const pendingCount = pendingOrders.length;
  const alertsCount = alertsQuery.data?.items.length ?? 0;
  const unreadCount = notificationsQuery.data?.unread ?? 0;
  const recentNotifications: NotificationItem[] = notificationsQuery.data?.items ?? [];

  return (
    <div className="dashboard-page">
      {/* 与 wallet / settings / activity 三个 console 页同样的终端 route header */}
      <div className="dashboard-page__console-route">
        <span>FalconX</span>
        <span className="dashboard-page__console-route-sep">/</span>
        <span>首页</span>
        <span className="dashboard-page__console-route-sep">/</span>
        <span className="dashboard-page__console-route-current">概览</span>
      </div>
      <header className="dashboard-page__hero">
        <div>
          <h2>账户概览</h2>
          <p className="dashboard-page__hero-sub">
            数据由 WebSocket 实时驱动（持仓 / 余额 / 通知 / 告警）；
            未覆盖项可点 <button type="button" className="fx-btn-link" onClick={handleRefresh}>刷新</button>
          </p>
        </div>
        <div className="dashboard-page__hero-actions">
          <button type="button" className="fx-btn-secondary" onClick={handleRefresh}>
            刷新
          </button>
          <button type="button" className="fx-btn-primary" onClick={() => onNavigateToMarket()}>
            去交易 →
          </button>
        </div>
      </header>

      {/* 4 个核心余额卡（WS 驱动：account.update + position.pnl） */}
      <section className="dashboard-stat-grid">
        <article className="dashboard-stat-card">
          <div className="dashboard-stat-card__label">总余额</div>
          <div className="dashboard-stat-card__value fx-num">
            ${formatNumber(accountQuery.data?.balance)}
          </div>
          <div className="dashboard-stat-card__sub">{accountQuery.data?.currency ?? "USDT"}</div>
        </article>
        <article className="dashboard-stat-card">
          <div className="dashboard-stat-card__label">可用</div>
          <div className="dashboard-stat-card__value fx-num">
            ${formatNumber(accountQuery.data?.available)}
          </div>
          <div className="dashboard-stat-card__sub">balance - frozen - margin</div>
        </article>
        <article className="dashboard-stat-card">
          <div className="dashboard-stat-card__label">占用保证金</div>
          <div className="dashboard-stat-card__value fx-num">
            ${formatNumber(accountQuery.data?.marginUsed)}
          </div>
          <div className="dashboard-stat-card__sub">在场 {positionStats.count} 笔持仓</div>
        </article>
        <article className="dashboard-stat-card">
          <div className="dashboard-stat-card__label">已实现盈亏（近 {closedStats.count} 笔平仓）</div>
          <div className={`dashboard-stat-card__value fx-num ${pnlColorClass(closedStats.realized)}`}>
            {closedStats.realizedAvailable
              ? `$${formatSignedPnl(closedStats.realized)}`
              : "—"}
          </div>
          <div className="dashboard-stat-card__sub">来自 position.closed 历史</div>
        </article>
        <article className="dashboard-stat-card">
          <div className="dashboard-stat-card__label">Swap 净影响（近 30 天）</div>
          {(() => {
            const s = swapSummaryQuery.data;
            const net = s ? Number(s.net) : null;
            const cls = net == null ? "" : net > 0 ? "fx-pnl-pos" : net < 0 ? "fx-pnl-neg" : "";
            const charge = s ? Number(s.totalCharge) : 0;
            const income = s ? Number(s.totalIncome) : 0;
            const hasData = s && (s.chargeCount > 0 || s.incomeCount > 0);
            return (
              <>
                <div className={`dashboard-stat-card__value fx-num ${cls}`}>
                  {net == null
                    ? "—"
                    : `$${formatSignedMoney(net, accountQuery.data?.currency)}`}
                </div>
                <div className="dashboard-stat-card__sub">
                  {hasData
                    ? `支付 -${formatMoney(charge, accountQuery.data?.currency)} / 收入 +${formatMoney(income, accountQuery.data?.currency)}`
                    : "暂无 Swap 结算"}
                </div>
              </>
            );
          })()}
        </article>
        {/* STAGE-14E1 Task6：账户级保证金水平（三态浮窗）+ 实际生效保证金模式 */}
        <article className="dashboard-stat-card">
          <div className="dashboard-stat-card__label">保证金水平</div>
          <div className="dashboard-stat-card__value">
            <MarginLevelIndicator
              marginLevel={marginLevel}
              marginLevelStatus={marginLevelStatus}
            />
          </div>
          <div className="dashboard-stat-card__sub">
            模式：
            {accountMarginMode === "ISOLATED"
              ? "逐仓"
              : accountMarginMode === "CROSS"
                ? "全仓"
                : "—"}
          </div>
        </article>
      </section>

      {/* 4 个快捷数（活跃持仓 / 挂单可点击跳到对应 tab） */}
      <section className="dashboard-stat-grid dashboard-stat-grid--secondary">
        <button
          type="button"
          className="dashboard-stat-card dashboard-stat-card--small dashboard-stat-card--clickable"
          onClick={() => onNavigateToMarket("positions")}
          title="点击查看持仓详情"
        >
          <div className="dashboard-stat-card__label">活跃持仓 →</div>
          <div className="dashboard-stat-card__value fx-num">{positionStats.count}</div>
        </button>
        <button
          type="button"
          className="dashboard-stat-card dashboard-stat-card--small dashboard-stat-card--clickable"
          onClick={() => onNavigateToMarket("pending")}
          title="点击查看挂单详情"
        >
          <div className="dashboard-stat-card__label">挂单 →</div>
          <div className="dashboard-stat-card__value fx-num">{pendingCount}</div>
        </button>
        <article className="dashboard-stat-card dashboard-stat-card--small">
          <div className="dashboard-stat-card__label">价格告警</div>
          <div className="dashboard-stat-card__value fx-num">{alertsCount}</div>
        </article>
        <article className="dashboard-stat-card dashboard-stat-card--small">
          <div className="dashboard-stat-card__label">未读通知</div>
          <div className="dashboard-stat-card__value fx-num">{unreadCount}</div>
        </article>
      </section>

      {/* 实时汇总 */}
      <section className="dashboard-summary-row">
        <article className="dashboard-summary-card">
          <div className="dashboard-summary-card__label">
            未实现盈亏（{liveUnrealized.source === "ws" ? "实时 WS" : liveUnrealized.source === "rest" ? "REST 初值" : "—"}）
          </div>
          <div className={`dashboard-summary-card__value fx-num ${pnlColorClass(liveUnrealized.value)}`}>
            {liveUnrealized.available
              ? `$${formatSignedPnl(liveUnrealized.value)}`
              : "—"}
          </div>
        </article>
        <article className="dashboard-summary-card">
          <div className="dashboard-summary-card__label">
            累计手续费（近 {tradeStats.count} 笔成交）
          </div>
          <div className="dashboard-summary-card__value fx-num">
            ${formatMoney(tradeStats.totalFee, accountQuery.data?.currency)}
          </div>
        </article>
      </section>

      {/* 实时持仓 + 挂单 mini 表 */}
      <section className="dashboard-recent-row">
        <article className="dashboard-recent-card">
          <header className="dashboard-recent-card__header">
            <h3>活跃持仓（按开仓时间倒序 前 5）</h3>
            <button type="button" className="fx-btn-link" onClick={() => onNavigateToMarket("positions")}>
              查看全部 →
            </button>
          </header>
          {positionStats.top5.length === 0 ? (
            <div className="fx-tab-empty">暂无持仓</div>
          ) : (
            <table className="fx-table fx-table--compact">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>方向</th>
                  <th className="fx-num-th">数量</th>
                  <th className="fx-num-th">开仓价</th>
                  <th className="fx-num-th">标记价</th>
                  <th className="fx-num-th">未实现盈亏</th>
                </tr>
              </thead>
              <tbody>
                {positionStats.top5.map((p) => {
                  const pnl = p.unrealizedPnlInAccount == null ? null : Number(p.unrealizedPnlInAccount);
                  const pnlClass = pnlColorClass(pnl);
                  const showQuoteRow = shouldShowQuoteRow(p.quoteCurrency, p.unrealizedPnlInQuote);
                  return (
                    <tr key={p.positionId}>
                      <td>{p.symbol}</td>
                      <td className={p.side === "BUY" ? "fx-long" : "fx-short"}>
                        {p.side === "BUY" ? "多" : "空"}
                      </td>
                      <td className="fx-num">{p.quantity}</td>
                      <td className="fx-num">{p.entryPrice}</td>
                      <td className="fx-num">{p.markPrice ?? "—"}</td>
                      <td className="fx-num">
                        <div className={`fx-pnl-dual ${pnlClass}`}>
                          <span className="fx-pnl-dual__main">
                            {p.unrealizedPnlInAccount == null ? "—" : formatPnl(p.unrealizedPnlInAccount)}
                            <span className="fx-pnl-dual__ccy">{ACCOUNT_CURRENCY}</span>
                          </span>
                          {showQuoteRow && (
                            <span
                              className="fx-pnl-dual__sub"
                              title="以持仓报价币计的未实现盈亏（账户币副口径）"
                            >
                              {formatPnl(p.unrealizedPnlInQuote)} {p.quoteCurrency}
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </article>

        <article className="dashboard-recent-card">
          <header className="dashboard-recent-card__header">
            <h3>挂单（最近 5）</h3>
            <button type="button" className="fx-btn-link" onClick={() => onNavigateToMarket("pending")}>
              查看全部 →
            </button>
          </header>
          {pendingTop5.length === 0 ? (
            <div className="fx-tab-empty">暂无挂单</div>
          ) : (
            <table className="fx-table fx-table--compact">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>类型</th>
                  <th>方向</th>
                  <th className="fx-num-th">数量</th>
                  <th className="fx-num-th">触发价</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                {pendingTop5.map((o) => (
                  <tr key={o.id}>
                    <td>{o.symbol}</td>
                    <td>{o.orderType}</td>
                    <td className={o.side === "BUY" ? "fx-long" : "fx-short"}>
                      {o.side === "BUY" ? "多" : "空"}
                    </td>
                    <td className="fx-num">{o.quantity}</td>
                    <td className="fx-num">{o.triggerPrice}</td>
                    <td>{o.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </article>
      </section>

      {/* 最近成交 + 最近通知 */}
      <section className="dashboard-recent-row">
        <article className="dashboard-recent-card">
          <header className="dashboard-recent-card__header">
            <h3>最近成交</h3>
            <button type="button" className="fx-btn-link" onClick={() => onNavigateToMarket("trades")}>
              查看全部 →
            </button>
          </header>
          {tradeStats.recent.length === 0 ? (
            <div className="fx-tab-empty">暂无成交</div>
          ) : (
            <table className="fx-table fx-table--compact">
              <thead>
                <tr>
                  <th>Symbol</th>
                  <th>方向</th>
                  <th>类型</th>
                  <th className="fx-num-th">数量</th>
                  <th className="fx-num-th">价格</th>
                  <th className="fx-num-th">已实现盈亏</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                {tradeStats.recent.map((t) => {
                  const pnl = t.realizedPnl == null ? null : Number(t.realizedPnl);
                  const pnlClass = pnlColorClass(pnl);
                  return (
                    <tr key={t.tradeId}>
                      <td>{t.symbol}</td>
                      <td className={t.side === "BUY" ? "fx-long" : "fx-short"}>
                        {t.side === "BUY" ? "多" : "空"}
                      </td>
                      <td>{t.tradeType}</td>
                      <td className="fx-num">{t.quantity}</td>
                      <td className="fx-num">{t.price}</td>
                      <td className={`fx-num ${pnlClass}`}>{pnl == null ? "—" : formatPnl(pnl)}</td>
                      <td className="fx-mono dashboard-recent-card__ts">
                        {formatTs(t.tradedAt)}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </article>

        <article className="dashboard-recent-card">
          <header className="dashboard-recent-card__header">
            <h3>最近通知</h3>
            {unreadCount > 0 && (
              <span className="dashboard-recent-card__badge">{unreadCount} 未读</span>
            )}
          </header>
          {recentNotifications.length === 0 ? (
            <div className="fx-tab-empty">暂无通知</div>
          ) : (
            <ul className="dashboard-notif-list">
              {recentNotifications.map((n) => (
                <li key={n.id} className={`dashboard-notif-list__item level-${n.level.toLowerCase()}`}>
                  <div className="dashboard-notif-list__row">
                    <span className={`dashboard-notif-list__level level-${n.level.toLowerCase()}`}>
                      {n.level === "INFO" ? "通知" : n.level === "WARN" ? "警告" : "重要"}
                    </span>
                    <time>{formatTs(n.createdAt)}</time>
                  </div>
                  <div className="dashboard-notif-list__title">{n.title}</div>
                  <div className="dashboard-notif-list__body">{n.body}</div>
                </li>
              ))}
            </ul>
          )}
        </article>
      </section>
    </div>
  );
}

function formatNumber(v: string | number | null | undefined): string {
  if (v === null || v === undefined || v === "") return "—";
  const num = typeof v === "number" ? v : Number(v);
  if (!Number.isFinite(num)) return String(v);
  // USDT 等法币口径统一 2 位小数；微小盈亏（< 0.01）再扩 4 位避免显示为 0.00
  const max = Math.abs(num) < 0.01 && num !== 0 ? 4 : 2;
  return num.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: max });
}

function formatTs(iso: string | null | undefined): string {
  if (!iso) return "—";
  // 与其他页面统一为 yyyy-MM-dd HH:mm:ss
  return iso.replace("T", " ").slice(0, 19);
}
