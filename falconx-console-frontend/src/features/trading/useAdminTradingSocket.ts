import { useEffect, useRef, useState } from "react";
import { ConsoleEnv } from "../../lib/config/env";

export type AdminSocketState = "idle" | "connecting" | "open" | "reconnecting" | "closed" | "error";

export interface AdminExposureUpdate {
  symbol: string;
  /** STAGE-14E2 Task1：per-symbol 报价币（QC，来源 SymbolSpec）；过渡期可能为 null。 */
  quoteCurrency: string | null;
  totalLongQty: string | null;
  totalShortQty: string | null;
  netExposure: string | null;
  netExposureUsd: string | null;
  quoteTs: string | null;
}

export interface AdminRiskActionChanged {
  actionId: number | null;
  symbol: string | null;
  actionType: string | null;
  triggerSource: string | null;
  triggerReason: string | null;
  active: boolean;
  occurredAt: string | null;
}

export interface AdminRiskSwitchChanged {
  switchKey: string;
  enabled: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
}

/** STAGE-7-WITHDRAW Phase 4 §4 commit C：admin.withdraw.status-changed 推送 payload。 */
export interface AdminWithdrawStatusChanged {
  withdrawId: string;
  userId: string;
  status: string;
  txHash: string | null;
  confirmations: number | null;
  failureReason: string | null;
  occurredAt: string | null;
}

/**
 * 管理端持仓 PnL 实时 patch 单条（admin.position.update 内的 item）。
 *
 * STAGE-14E2 Task5：硬切双币 —— 删旧单币 unrealizedPnl，改为
 * quoteCurrency / fxRate / unrealizedPnlInQuote（报价币副口径）/
 * unrealizedPnlInAccount（账户币 AC 主口径，展示用），对齐 E1 客户端 computeDualPnl。
 */
export interface AdminPositionPnlPatch {
  positionId: string;
  userId: string;
  side: string | null;
  markPrice: string | null;
  quoteCurrency: string | null;
  fxRate: string | null;
  unrealizedPnlInQuote: string | null;
  unrealizedPnlInAccount: string | null;
}

/** 管理端持仓 PnL 实时 patch 整批（按 symbol 200ms 节流） */
export interface AdminPositionPnlUpdate {
  symbol: string;
  quoteTs: string | null;
  items: AdminPositionPnlPatch[];
}

/** 管理端持仓汇总 patch（平台 totalUnrealizedPnl，全局 500ms 节流） */
export interface AdminPositionSummaryUpdate {
  totalUnrealizedPnl: string | null;
  computedAt: string | null;
}

interface Handlers {
  onExposureUpdate?: (update: AdminExposureUpdate) => void;
  onRiskActionChanged?: (event: AdminRiskActionChanged) => void;
  onRiskSwitchChanged?: (event: AdminRiskSwitchChanged) => void;
  onWithdrawStatusChanged?: (event: AdminWithdrawStatusChanged) => void;
  onPositionPnlUpdate?: (update: AdminPositionPnlUpdate) => void;
  onPositionSummaryUpdate?: (update: AdminPositionSummaryUpdate) => void;
}

const MAX_RECONNECT_DELAY_MS = 30_000;
const BASE_RECONNECT_DELAY_MS = 1_000;
const ADMIN_CHANNELS = ["admin.exposure", "admin.risk-actions", "admin.risk-switches", "admin.withdraws", "admin.positions"];

/**
 * STAGE-2-REALTIME-DATA Phase 4：管理端 WS hook。
 *
 * 与 falconx-frontend/useTradingSocket 共用 gateway /ws/v1/trading，
 * 按 token iss 字段分流。本 hook 订阅 3 个 admin 频道：
 *   admin.exposure       → admin.exposure.update（200ms/symbol 节流）
 *   admin.risk-actions   → admin.risk-action.changed（事件型）
 *   admin.risk-switches  → admin.risk-switch.changed（事件型）
 *
 * 注意：admin 不发 account.snapshot 初始包；订阅后服务端 push 才会推数据。
 */
export function useAdminTradingSocket(token: string | null, handlers: Handlers): AdminSocketState {
  const [state, setState] = useState<AdminSocketState>("idle");
  const handlersRef = useRef(handlers);
  useEffect(() => {
    handlersRef.current = handlers;
  }, [handlers]);

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!token) {
      setState("idle");
      return;
    }

    let socket: WebSocket | null = null;
    let shouldReconnect = true;
    let reconnectAttempt = 0;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;

    const clearReconnectTimer = () => {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
    };

    const connect = () => {
      clearReconnectTimer();
      setState(reconnectAttempt === 0 ? "connecting" : "reconnecting");
      const url = `${ConsoleEnv.wsBaseUrl}/ws/v1/trading?token=${encodeURIComponent(token)}`;
      socket = new WebSocket(url);

      socket.addEventListener("open", () => {
        reconnectAttempt = 0;
        setState("open");
        try {
          socket?.send(
            JSON.stringify({
              type: "subscribe",
              requestId: `admin-sub-${Date.now()}`,
              channels: ADMIN_CHANNELS,
            }),
          );
        } catch {
          // ignore send error
        }
      });

      socket.addEventListener("message", (event) => {
        if (typeof event.data !== "string") return;
        try {
          const envelope = JSON.parse(event.data) as {
            type?: string;
            data?: Record<string, unknown>;
          };
          const d = envelope.data ?? {};
          switch (envelope.type) {
            case "admin.exposure.update": {
              handlersRef.current.onExposureUpdate?.({
                symbol: String(d.symbol ?? ""),
                quoteCurrency: (d.quoteCurrency as string | null | undefined) ?? null,
                totalLongQty: (d.totalLongQty as string | null | undefined) ?? null,
                totalShortQty: (d.totalShortQty as string | null | undefined) ?? null,
                netExposure: (d.netExposure as string | null | undefined) ?? null,
                netExposureUsd: (d.netExposureUsd as string | null | undefined) ?? null,
                quoteTs: (d.quoteTs as string | null | undefined) ?? null,
              });
              break;
            }
            case "admin.risk-action.changed": {
              handlersRef.current.onRiskActionChanged?.({
                actionId: d.actionId == null ? null : Number(d.actionId),
                symbol: (d.symbol as string | null | undefined) ?? null,
                actionType: (d.actionType as string | null | undefined) ?? null,
                triggerSource: (d.triggerSource as string | null | undefined) ?? null,
                triggerReason: (d.triggerReason as string | null | undefined) ?? null,
                active: Boolean(d.active),
                occurredAt: (d.occurredAt as string | null | undefined) ?? null,
              });
              break;
            }
            case "admin.risk-switch.changed": {
              handlersRef.current.onRiskSwitchChanged?.({
                switchKey: String(d.switchKey ?? ""),
                enabled: Boolean(d.enabled),
                updatedBy: (d.updatedBy as string | null | undefined) ?? null,
                updatedAt: (d.updatedAt as string | null | undefined) ?? null,
              });
              break;
            }
            case "admin.withdraw.status-changed": {
              handlersRef.current.onWithdrawStatusChanged?.({
                withdrawId: String(d.withdrawId ?? ""),
                userId: String(d.userId ?? ""),
                status: String(d.status ?? ""),
                txHash: (d.txHash as string | null | undefined) ?? null,
                confirmations: d.confirmations == null ? null : Number(d.confirmations),
                failureReason: (d.failureReason as string | null | undefined) ?? null,
                occurredAt: (d.occurredAt as string | null | undefined) ?? null,
              });
              break;
            }
            case "admin.position.update": {
              const rawItems = Array.isArray(d.items) ? (d.items as Array<Record<string, unknown>>) : [];
              handlersRef.current.onPositionPnlUpdate?.({
                symbol: String(d.symbol ?? ""),
                quoteTs: (d.quoteTs as string | null | undefined) ?? null,
                items: rawItems.map((it) => ({
                  positionId: String(it.positionId ?? ""),
                  userId: String(it.userId ?? ""),
                  side: (it.side as string | null | undefined) ?? null,
                  markPrice: (it.markPrice as string | null | undefined) ?? null,
                  quoteCurrency: (it.quoteCurrency as string | null | undefined) ?? null,
                  fxRate: (it.fxRate as string | null | undefined) ?? null,
                  unrealizedPnlInQuote: (it.unrealizedPnlInQuote as string | null | undefined) ?? null,
                  unrealizedPnlInAccount: (it.unrealizedPnlInAccount as string | null | undefined) ?? null,
                })),
              });
              break;
            }
            case "admin.position.summary": {
              handlersRef.current.onPositionSummaryUpdate?.({
                totalUnrealizedPnl: (d.totalUnrealizedPnl as string | null | undefined) ?? null,
                computedAt: (d.computedAt as string | null | undefined) ?? null,
              });
              break;
            }
            case "subscribed":
            case "unsubscribed":
            case "pong":
            case "error":
              break;
            default:
              break;
          }
        } catch {
          // ignore parse error
        }
      });

      socket.addEventListener("error", () => setState("error"));

      socket.addEventListener("close", (event) => {
        if (!shouldReconnect) {
          setState("closed");
          return;
        }
        if (event.code === 4001 || event.code === 1008) {
          // 鉴权失败：不要无限重连
          shouldReconnect = false;
          setState("closed");
          return;
        }
        const delay = Math.min(BASE_RECONNECT_DELAY_MS * 2 ** reconnectAttempt, MAX_RECONNECT_DELAY_MS);
        reconnectAttempt += 1;
        reconnectTimer = setTimeout(connect, delay);
      });
    };

    connect();

    return () => {
      shouldReconnect = false;
      clearReconnectTimer();
      socket?.close(1000);
    };
  }, [token]);
  /* eslint-enable react-hooks/set-state-in-effect */

  return state;
}
