import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bell, X } from "lucide-react";
import { useAuthStore } from "../auth/authStore";
import {
  getNotificationUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from "./tradingApi";
import type { NotificationItem, NotificationLevel } from "./tradingTypes";

const LEVEL_LABEL: Record<NotificationLevel, string> = {
  INFO: "通知",
  WARN: "警告",
  CRITICAL: "重要",
};

export function NotificationCenter() {
  const session = useAuthStore((s) => s.session);
  const token = session?.accessToken ?? null;
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);

  const unreadQuery = useQuery({
    queryKey: ["trading", "notifications", "unread"],
    queryFn: () => (token ? getNotificationUnreadCount(token).then((r) => r.unread) : 0),
    enabled: Boolean(token),
    staleTime: 30_000,
  });

  const listQuery = useQuery({
    queryKey: ["trading", "notifications", "list"],
    queryFn: () => (token ? listNotifications(token, 1, 50) : Promise.reject(new Error("no token"))),
    enabled: Boolean(token) && open,
  });

  const markReadMutation = useMutation({
    mutationFn: (id: string) => {
      if (!token) throw new Error("no token");
      return markNotificationRead(token, id);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["trading", "notifications"] });
    },
  });

  const markAllMutation = useMutation({
    mutationFn: () => {
      if (!token) throw new Error("no token");
      return markAllNotificationsRead(token);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["trading", "notifications"] });
    },
  });

  // 监听 useTradingSocket 派发的 notification.created → 刷未读数 + 列表
  useEffect(() => {
    const handler = () => {
      void queryClient.invalidateQueries({ queryKey: ["trading", "notifications"] });
    };
    window.addEventListener("falconx:notification:created", handler);
    return () => window.removeEventListener("falconx:notification:created", handler);
  }, [queryClient]);

  // ESC 关抽屉
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [open]);

  const unread = unreadQuery.data ?? 0;
  const items: NotificationItem[] = listQuery.data?.items ?? [];

  return (
    <>
      <button
        type="button"
        className="notification-bell"
        title="通知"
        aria-label={`通知 ${unread > 0 ? `（${unread} 条未读）` : ""}`}
        onClick={() => setOpen(true)}
      >
        <Bell size={16} aria-hidden="true" />
        {unread > 0 && <span className="notification-bell__badge">{unread > 99 ? "99+" : unread}</span>}
      </button>

      {/*
        Portal 到 document.body：父级 .terminal-topbar 用了 backdrop-filter，会创建新的
        containing block，导致 position:fixed 被困在 topbar 区域内，覆盖不了整个视口。
        Portal 出去后 inset:0 才能真正贴满 viewport。
      */}
      {open && createPortal(
        <div className="notification-overlay" onClick={() => setOpen(false)}>
          <aside className="notification-drawer" onClick={(e) => e.stopPropagation()}>
            <header className="notification-drawer__header">
              <h3>通知中心{unread > 0 && <span className="notification-drawer__unread">{unread}</span>}</h3>
              <div className="notification-drawer__actions">
                {unread > 0 && (
                  <button
                    type="button"
                    className="fx-btn-secondary fx-btn-xs"
                    disabled={markAllMutation.isPending}
                    onClick={() => markAllMutation.mutate()}
                  >
                    全部已读
                  </button>
                )}
                <button type="button" className="notification-drawer__close" onClick={() => setOpen(false)} aria-label="关闭">
                  <X size={16} />
                </button>
              </div>
            </header>

            <div className="notification-drawer__body">
              {listQuery.isLoading && <div className="fx-tab-empty">加载中…</div>}
              {!listQuery.isLoading && items.length === 0 && <div className="fx-tab-empty">暂无通知</div>}
              {items.map((item) => (
                <article
                  key={item.id}
                  className={`notification-item ${item.status === "UNREAD" ? "unread" : ""} level-${item.level.toLowerCase()}`}
                  onClick={() => {
                    if (item.status === "UNREAD") markReadMutation.mutate(item.id);
                  }}
                >
                  <div className="notification-item__row">
                    <span className={`notification-item__level level-${item.level.toLowerCase()}`}>{LEVEL_LABEL[item.level]}</span>
                    <time>{new Date(item.createdAt).toLocaleString("zh-CN", { hour12: false })}</time>
                  </div>
                  <div className="notification-item__title">{item.title}</div>
                  <div className="notification-item__body">{item.body}</div>
                </article>
              ))}
            </div>
          </aside>
        </div>,
        document.body
      )}
    </>
  );
}
