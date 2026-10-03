import { useState, type ReactNode } from "react";
import type { PositionPnlUpdate, TradingSocketState } from "./useTradingSocket";
import { PositionsTable } from "./PositionsTable";
import { ClosedPositionsTable } from "./ClosedPositionsTable";
import { OrdersTable } from "./OrdersTable";
import { TradesTable } from "./TradesTable";
import { PendingOrdersTable } from "./PendingOrdersTable";
import { PriceAlertsTable } from "./PriceAlertsTable";

export type TabKey = "positions" | "history" | "pending" | "orders" | "trades" | "alerts";

const TABS: { key: TabKey; label: string }[] = [
  { key: "positions", label: "持仓" },
  { key: "history", label: "历史" },
  { key: "pending", label: "挂单" },
  { key: "orders", label: "订单" },
  { key: "trades", label: "成交" },
  { key: "alerts", label: "告警" },
];

interface Props {
  /** WS 推送累积的 pnlMap，从 TradingTerminal 透传 */
  pnlMap: Map<string, PositionPnlUpdate>;
  /** WS 连接状态指示，从 TradingTerminal 透传 */
  socketState: TradingSocketState;
  /**
   * 由父组件触发的目标 tab（如从首页 stat 卡点击跳过来）。
   * 每次该 prop 引用变化（含相同 key 时用 nonce 包装），useEffect 同步到 internal active。
   * 不传则保持 internal 默认 "positions"。
   */
  forceActiveTab?: { key: TabKey; nonce: number } | null;
  /**
   * 后端聚合并 500ms 节流推送的「总未实现盈亏」（user.position.summary）。
   * PositionsTable 顶部「总未实现盈亏」直接显示此值；null 时 fallback 客户端累加。
   */
  serverTotalUnrealizedPnl: string | null;
  /** 注入到 §04 panel-head 右侧的扩展槽（如「返回 K 线」反向跳转 chip）。 */
  headerExtras?: ReactNode;
}

/**
 * 持仓 / 历史 / 挂单 / 订单 / 成交 / 告警 6 tab 切换面板。
 *
 * <p>所有 WebSocket 订阅 / pnlMap 缓存 / toast 渲染均已上提到 TradingTerminal，
 * 跨视图（首页 / 市场）切换时 WS 不重连、pnlMap 不丢失。本组件只负责 tab 切换 +
 * 把 pnlMap 传给 PositionsTable。
 */
export function TradingTabs({ pnlMap, socketState, forceActiveTab, serverTotalUnrealizedPnl, headerExtras }: Props) {
  const [activeState, setActiveState] = useState<{
    key: TabKey;
    consumedForceNonce: number | null;
  }>({
    key: "positions",
    consumedForceNonce: null
  });
  const active =
    forceActiveTab && activeState.consumedForceNonce !== forceActiveTab.nonce
      ? forceActiveTab.key
      : activeState.key;
  const setActive = (key: TabKey) =>
    setActiveState({
      key,
      consumedForceNonce: forceActiveTab?.nonce ?? null
    });

  const activeLabel = TABS.find((t) => t.key === active)?.label ?? "";
  return (
    <section className="fx-trading-tabs" aria-label="交易面板">
      <div className="fx-panel-head">
        <span className="fx-panel-head__num">§04</span>
        <h2 className="fx-panel-head__title">交易明细</h2>
        <span className="fx-panel-head__hint">
          <b>{activeLabel}</b> · 实时{" "}
          <span className={`fx-ws-indicator fx-ws-${socketState}`} title={`实时推送：${socketState}`}>
            {socketState === "open" ? "●" : "○"}
          </span>
        </span>
        {headerExtras}
      </div>
      <div className="fx-tab-bar">
        {TABS.map((t, idx) => (
          <button
            type="button"
            key={t.key}
            className={`fx-tab-button ${active === t.key ? "active" : ""}`}
            onClick={() => setActive(t.key)}
          >
            <span className="fx-tab-button__num">{String(idx + 1).padStart(2, "0")}</span>
            {t.label}
          </button>
        ))}
      </div>
      <div className="fx-tab-panel" role="region" aria-label={`${activeLabel}列表滚动区`}>
        {active === "positions" && (
          <PositionsTable active pnlMap={pnlMap} serverTotalUnrealizedPnl={serverTotalUnrealizedPnl} />
        )}
        {active === "history" && <ClosedPositionsTable active />}
        {active === "pending" && <PendingOrdersTable active={active === "pending"} />}
        {active === "orders" && <OrdersTable active />}
        {active === "trades" && <TradesTable active />}
        {active === "alerts" && <PriceAlertsTable active={active === "alerts"} />}
      </div>
    </section>
  );
}
