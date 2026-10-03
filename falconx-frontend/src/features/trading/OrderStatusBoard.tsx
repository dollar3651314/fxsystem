import { useQuery } from "@tanstack/react-query";
import { useAuthStore } from "../auth/authStore";
import { formatMoney, formatSignedPnl } from "../../lib/precision";
import {
  getAccount,
  getPositionSummary,
  listPendingOrders,
  listPositions
} from "./tradingApi";
import { composeEquity, marginStatusLabel, trimPercent } from "./orderStatusFormat";

type OrderStatusBoardProps = {
  /** WS user.position.summary（500ms 节流）实时总未实现盈亏；null = 未收到推送，fallback REST。 */
  totalUnrealizedPnl: string | null;
  /** WS account.update 实时权益；null fallback REST /accounts/me。 */
  accountEquity: string | null;
  /** WS account.update 实时保证金水平（百分数字符串）。 */
  marginLevel: string | null;
  /** HEALTHY / MARGIN_CALL / STOP_OUT */
  marginLevelStatus: string | null;
  /** 账户级实际生效保证金模式（ISOLATED / CROSS）。 */
  accountMarginMode: string | null;
};

/**
 * §00 常驻订单数据看板（2026-06-04）。
 *
 * 挂在 TerminalTopbar 之下、所有视图（市场/首页/钱包/...）常驻的一条 slim 指标带：
 * 持仓数 / 挂单数 / 未实现盈亏 / 权益 / 可用 / 保证金水平。
 *
 * 实时链路（全部复用既有管线，无新增契约）：
 * - 盈亏/权益/保证金水平：trading WS 直推（user.position.summary 500ms + account.update），
 *   由 TradingTerminal 持有 state 经 props 注入；REST 仅作首屏 fallback；
 * - 持仓数/挂单数/可用：React Query 共享 dashboard 缓存 key —— 成交/平仓/强平/余额变更等
 *   WS 事件已在 TradingTerminal 统一 invalidate（refetchTradingAndDashboard /
 *   handleAccountBalanceChanged），本组件自动跟随刷新。
 */
export function OrderStatusBoard({
  totalUnrealizedPnl,
  accountEquity,
  marginLevel,
  marginLevelStatus,
  accountMarginMode
}: OrderStatusBoardProps) {
  const token = useAuthStore((state) => state.session?.accessToken);
  const baseQueryOpts = {
    enabled: Boolean(token),
    staleTime: 30_000,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false
  } as const;

  // 与 DashboardPage 完全同 key：双方挂载时共享缓存与请求，WS invalidate 一处生效
  const accountQuery = useQuery({
    queryKey: ["dashboard", "account"],
    queryFn: () => getAccount(token!),
    ...baseQueryOpts
  });
  const openPositionsQuery = useQuery({
    queryKey: ["dashboard", "positions", "OPEN"],
    queryFn: () => listPositions(token!, 1, 100, "OPEN"),
    ...baseQueryOpts
  });
  const pendingOrdersQuery = useQuery({
    queryKey: ["dashboard", "pending-orders"],
    queryFn: () => listPendingOrders(token!, 1, 50),
    ...baseQueryOpts
  });
  const summaryQuery = useQuery({
    queryKey: ["dashboard", "position-summary"],
    queryFn: () => getPositionSummary(token!),
    ...baseQueryOpts
  });

  const currency = accountQuery.data?.currency ?? "USDT";
  const positionCount = openPositionsQuery.data?.total;
  const pendingCount = pendingOrdersQuery.data?.total;
  // WS 实时值优先，REST 初值兜底（页面刚载入还没收到第一条推送时）
  const pnl = totalUnrealizedPnl ?? summaryQuery.data?.totalUnrealizedPnl ?? null;
  // 净值 = 余额 + 实时未实现盈亏。WS account.update 是事件驱动（余额/保证金事件才推），
  // 两次事件之间 equity 不随行情走 —— 这里用「REST 余额（事件时已被 invalidate 刷新）+
  // WS 实时盈亏（500ms）」本地合成，让净值跟随每条 PnL 推送跳动；双源未齐时退回推送/REST 快照。
  const equity = composeEquity(accountQuery.data?.balance, pnl)
    ?? accountEquity
    ?? accountQuery.data?.equity
    ?? null;
  const available = accountQuery.data?.available ?? null;
  const level = marginLevel ?? accountQuery.data?.marginLevel ?? null;
  const levelStatus = marginLevelStatus ?? accountQuery.data?.marginLevelStatus ?? null;

  const pnlNumber = Number(pnl);
  const pnlTone =
    pnl === null || !Number.isFinite(pnlNumber) || pnlNumber === 0
      ? ""
      : pnlNumber > 0
        ? " order-status-board__value--up"
        : " order-status-board__value--down";

  return (
    <section className="order-status-board" aria-label="订单数据看板">
      <Metric label="持仓" value={positionCount != null ? String(positionCount) : "—"} />
      <Metric label="挂单" value={pendingCount != null ? String(pendingCount) : "—"} />
      <Metric
        label="未实现盈亏"
        value={pnl !== null ? `${formatSignedPnl(pnl)} ${currency}` : "—"}
        valueClassName={pnlTone}
        live
      />
      <Metric label="净值" value={equity !== null ? formatMoney(equity, currency) : "—"} live />
      <Metric label="可用" value={available !== null ? formatMoney(available, currency) : "—"} />
      <div className="order-status-board__metric">
        <span className="order-status-board__label">保证金水平</span>
        <span
          className={`order-status-board__value order-status-board__level order-status-board__level--${(levelStatus ?? "none").toLowerCase()}`}
        >
          {level !== null ? `${trimPercent(level)}%` : "—"}
          {levelStatus ? <i>{marginStatusLabel(levelStatus)}</i> : null}
        </span>
      </div>
      {accountMarginMode ? (
        <span className="order-status-board__mode">
          {accountMarginMode === "CROSS" ? "全仓" : "逐仓"}
        </span>
      ) : null}
    </section>
  );
}

function Metric({
  label,
  value,
  valueClassName = "",
  live = false
}: {
  label: string;
  value: string;
  valueClassName?: string;
  /** WS 直推的实时字段，加小圆点标识 */
  live?: boolean;
}) {
  return (
    <div className="order-status-board__metric">
      <span className="order-status-board__label">
        {label}
        {live ? <i className="order-status-board__live-dot" aria-hidden="true" /> : null}
      </span>
      <span className={`order-status-board__value${valueClassName}`}>{value}</span>
    </div>
  );
}

