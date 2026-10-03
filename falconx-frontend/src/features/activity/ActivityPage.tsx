import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, ListChecks } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import { ClosedPositionsTable } from "../trading/ClosedPositionsTable";
import { OrdersTable } from "../trading/OrdersTable";
import { TradesTable } from "../trading/TradesTable";
import { PendingOrdersTable } from "../trading/PendingOrdersTable";
import { PriceAlertsTable } from "../trading/PriceAlertsTable";
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "../trading/tradingApi";
import type { NotificationItem } from "../trading/tradingTypes";

type ActivityTab = "history" | "pending" | "orders" | "trades" | "alerts" | "notifications";

interface TabSpec {
  key: ActivityTab;
  num: string;
  label: string;
  sub: string;
}

const TABS: TabSpec[] = [
  { key: "history", num: "01", label: "持仓历史", sub: "已平仓 / 强平" },
  { key: "pending", num: "02", label: "挂单", sub: "限价 / 止损" },
  { key: "orders", num: "03", label: "订单", sub: "市价 / 限价历史" },
  { key: "trades", num: "04", label: "成交", sub: "成交流水" },
  { key: "alerts", num: "05", label: "价格告警", sub: "ACTIVE / 历史" },
  { key: "notifications", num: "06", label: "通知", sub: "站内消息" },
];

/**
 * 活动页（console 风格）：历史 / 挂单 / 订单 / 成交 / 告警 / 通知 6 tab。
 * tab 头部用 numbered + small-caps + 未读 badge；body 复用 market 视图的表组件。
 */
export function ActivityPage() {
  const [tab, setTab] = useState<ActivityTab>("history");
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;

  // 仅取 unread 数量用于通知 tab 角标，page=1 size=1 足以拿到 unread total（后端 list 返回 total + unread）
  const unreadQuery = useQuery({
    queryKey: ["activity", "notifications", "unread-badge"],
    queryFn: () => listNotifications(token!, 1, 1),
    enabled: Boolean(token),
    staleTime: 10_000,
  });
  const unread = unreadQuery.data?.unread ?? 0;

  return (
    <main className="fx-console-page">
      <header className="fx-console-header">
        <div className="fx-console-header__row">
          <div className="fx-console-route">
            <span className="fx-console-route__icon">
              <ListChecks size={13} strokeWidth={2.2} aria-hidden="true" />
            </span>
            <span>FalconX</span>
            <span className="fx-console-route__sep">/</span>
            <span>活动</span>
            <span className="fx-console-route__sep">/</span>
            <span className="fx-console-route__current">
              {TABS.find((t) => t.key === tab)?.label ?? ""}
            </span>
          </div>
          <div className="fx-console-meta">
            <span className="fx-dot fx-dot--muted">{TABS.length} 个分类</span>
          </div>
        </div>
      </header>

      <div className="fx-console-page__body">
        <section className="fx-console-section">
          <nav className="activity-tabs" role="tablist">
            {TABS.map((t) => (
              <button
                key={t.key}
                role="tab"
                aria-selected={tab === t.key}
                type="button"
                className={tab === t.key ? "activity-tab active" : "activity-tab"}
                onClick={() => setTab(t.key)}
                title={t.sub}
              >
                <span className="activity-tab__num">{t.num}</span>
                {t.label}
                {t.key === "notifications" && unread > 0 && (
                  <span className="activity-tab__badge" aria-label={`${unread} 未读`}>
                    {unread > 99 ? "99+" : unread}
                  </span>
                )}
              </button>
            ))}
          </nav>

          <div className="activity-panel">
            {tab === "history" && <ClosedPositionsTable active />}
            {tab === "pending" && <PendingOrdersTable active />}
            {tab === "orders" && <OrdersTable active />}
            {tab === "trades" && <TradesTable active />}
            {tab === "alerts" && <PriceAlertsTable active />}
            {tab === "notifications" && <NotificationsList />}
          </div>
        </section>
      </div>
    </main>
  );
}

function NotificationsList() {
  const session = useAuthStore((s) => s.session);
  const queryClient = useQueryClient();
  const token = session?.accessToken ?? null;

  const [page, setPage] = useState(1);
  const query = useQuery({
    queryKey: ["activity", "notifications", page],
    queryFn: () => listNotifications(token!, page, 30),
    enabled: Boolean(token),
    staleTime: 5_000,
  });

  const markReadMutation = useMutation({
    mutationFn: (id: string) => markNotificationRead(token!, id),
    onSuccess: () => {
      // refetchType: "all" 强制连 inactive 的 unread-badge 也立刻 refetch；
      // 否则页面上 tab 角标 (依赖 unreadQuery) 在 mark all 后还是显示旧数字。
      void queryClient.invalidateQueries({ queryKey: ["activity", "notifications"], refetchType: "all" });
      void queryClient.invalidateQueries({ queryKey: ["trading", "notifications"], refetchType: "all" });
    },
  });
  const markAllMutation = useMutation({
    mutationFn: () => markAllNotificationsRead(token!),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["activity", "notifications"], refetchType: "all" });
      void queryClient.invalidateQueries({ queryKey: ["trading", "notifications"], refetchType: "all" });
    },
  });

  const items: NotificationItem[] = query.data?.items ?? [];
  const total = query.data?.total ?? 0;
  const unread = query.data?.unread ?? 0;
  const totalPages = Math.max(1, Math.ceil(total / 30));

  return (
    <div className="fx-tab-content">
      <div className="activity-panel__head">
        <span>
          共 <b>{total}</b> 条 · 未读{" "}
          <b className={unread > 0 ? "fx-pnl-pos" : ""}>{unread}</b>
        </span>
        <button
          type="button"
          className="fx-ghost-btn"
          disabled={unread === 0 || markAllMutation.isPending}
          onClick={() => markAllMutation.mutate()}
        >
          全部标记已读
        </button>
      </div>

      {query.isLoading && <div className="fx-tab-empty">加载中…</div>}
      {!query.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无通知</div>}
      {items.length > 0 && (
        <table className="fx-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>级别</th>
              <th>标题</th>
              <th>内容</th>
              <th>状态</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {items.map((n) => (
              <tr key={n.id} className={n.status === "UNREAD" ? "activity-notification--unread" : ""}>
                <td>{n.createdAt?.replace("T", " ").slice(0, 19) ?? "—"}</td>
                <td>
                  <span className={`activity-level-pill level-${n.level.toLowerCase()}`}>
                    <Bell size={11} aria-hidden="true" /> {n.level}
                  </span>
                </td>
                <td>{n.title}</td>
                <td className="activity-notification__body" title={n.body}>
                  {n.body}
                </td>
                <td>{n.status === "UNREAD" ? "未读" : "已读"}</td>
                <td>
                  {n.status === "UNREAD" && (
                    <button
                      type="button"
                      className="fx-ghost-btn"
                      disabled={markReadMutation.isPending}
                      onClick={() => markReadMutation.mutate(n.id)}
                    >
                      标记已读
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {totalPages > 1 && (
        <div className="activity-pagination">
          <button
            type="button"
            className="fx-ghost-btn"
            disabled={page <= 1}
            onClick={() => setPage((p) => p - 1)}
          >
            上一页
          </button>
          <span className="activity-pagination__info">
            第 {page} / {totalPages} 页
          </span>
          <button
            type="button"
            className="fx-ghost-btn"
            disabled={page >= totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
          </button>
        </div>
      )}
    </div>
  );
}
