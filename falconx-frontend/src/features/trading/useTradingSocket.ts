import { useEffect, useRef, useState } from "react";
import { wsBaseUrl } from "../../lib/config";
import { notifyAuthExpired } from "../../lib/authEvents";

export type TradingSocketState = "idle" | "connecting" | "open" | "reconnecting" | "closed" | "error";

export interface PositionPnlUpdate {
  positionId: string;
  symbol: string;
  side: string;
  markPrice: string | null;
  /** STAGE-14E1: 持仓结算币（settlement quote ccy），区别于品种标的币。 */
  quoteCurrency: string | null;
  /** STAGE-14E1: 结算币 → 账户币的换算汇率（数值字符串）。 */
  fxRate: string | null;
  /** STAGE-14E1: 以结算币计的未实现盈亏。 */
  unrealizedPnlInQuote: string | null;
  /** STAGE-14E1: 以账户币（USDT）计的未实现盈亏，替代旧单币 unrealizedPnl。 */
  unrealizedPnlInAccount: string | null;
  /** STAGE-14E1: 逐仓占用保证金；CROSS 模式为 null。 */
  isolatedMargin: string | null;
  liquidationDistance: string | null;
  quoteTs: string | null;
}

export interface PriceAlertTriggered {
  alertId: string;
  symbol: string;
  direction: "ABOVE" | "BELOW";
  targetPrice: string;
  triggeredPrice: string;
  triggerCount: number;
  remainingTriggers: number;
  exhausted: boolean;
  note: string | null;
  triggeredAt: string;
}

export interface OrderFilledEvent {
  orderNo: string;
  symbol: string;
  side: string;
  filledQty: string | null;
  avgPrice: string | null;
}

export interface PositionClosedEvent {
  positionId: string;
  symbol: string;
  side: string;
  status: "CLOSED" | "LIQUIDATED";
  realizedPnl: string | null;
  closePrice: string | null;
  liquidationReason: string | null;
}

export interface MarginAddedEvent {
  positionId: string;
  symbol: string;
  amount: string | null;
}

export interface NotificationCreatedEvent {
  id: string;
  type: string;
  level: "INFO" | "WARN" | "CRITICAL";
  title: string;
  body: string;
  relatedKey: string | null;
  relatedId: string | null;
  createdAt: string;
}

export interface AccountBalanceChangedEvent {
  currency: string;
  balance: string | null;
  available: string | null;
  delta: string | null;
  reason: string | null;
}

/**
 * STAGE-14E1：账户级实时状态（account.update / account.snapshot 硬切双币 + marginLevel）。
 * accountMarginMode 是账户级当前实际生效模式，区别于 preferencesStore.defaultMarginMode（本地下单偏好）。
 */
export interface AccountStateUpdate {
  equity: string | null;
  marginLevel: string | null;
  /** HEALTHY / MARGIN_CALL / STOP_OUT */
  marginLevelStatus: string | null;
  accountMarginMode: string | null;
}

/** STAGE-2-REALTIME-DATA：单用户「总未实现盈亏」聚合，500ms 节流。 */
export interface UserPositionSummaryUpdate {
  totalUnrealizedPnl: string | null;
  computedAt: string | null;
}

interface Handlers {
  onOrderFilled?: (event?: OrderFilledEvent) => void;
  onOrderRejected?: (reason: string | null) => void;
  onPositionClosed?: (event?: PositionClosedEvent) => void;
  /** STAGE-2-REALTIME-DATA Phase 1：PnL 增量推送（不 refetch，仅 patch UI）。 */
  onPositionPnl?: (update: PositionPnlUpdate) => void;
  /** STAGE-4-PRICE-ALERT：价格告警触发（Toast + refetch alerts）。 */
  onPriceAlertTriggered?: (event: PriceAlertTriggered) => void;
  /** 手动/系统补充保证金。 */
  onMarginAdded?: (event: MarginAddedEvent) => void;
  /** 账户余额/可用变更（频率高，调用方应自行节流）。 */
  onAccountBalanceChanged?: (event: AccountBalanceChangedEvent) => void;
  /** STAGE-14E1：账户级实时状态（equity / marginLevel / marginLevelStatus / accountMarginMode）。 */
  onAccountUpdate?: (event: AccountStateUpdate) => void;
  /** STAGE-8-NOTIFICATION：新站内信推送，调用方刷未读数 + 弹 toast。 */
  onNotificationCreated?: (event: NotificationCreatedEvent) => void;
  /** 单用户总未实现盈亏 patch（user.position.summary，每 500ms 一次）。 */
  onPositionSummary?: (event: UserPositionSummaryUpdate) => void;
}

const MAX_RECONNECT_DELAY_MS = 30_000;
const BASE_RECONNECT_DELAY_MS = 1_000;
const SUBSCRIBE_CHANNELS = [
  "orders",
  "positions",
  "trades",
  "account",
  "margin",
  "ledger",
  "liquidations",
  "price-alerts",  // STAGE-4-PRICE-ALERT
  "notifications", // STAGE-8-NOTIFICATION
];

/**
 * 接入 trading-core /ws/v1/trading。
 *
 * 真实协议（来自 TradingUserWebSocketSessionRegistry）：
 * 1. 客户端连接成功后必须发 `subscribe` 消息：
 *    {type:"subscribe", requestId, channels:["orders","positions","trades","account",...]}
 *    服务端只对已订阅 channel 推送（未订阅 recipient=0）
 * 2. 服务端推送 envelope：{type, channel, requestId?, data, ts}
 *    - "order.update"        订单状态变化（含 REJECTED / FILLED）
 *    - "position.update"     持仓变化（含 close / TP-SL / margin 补充）
 *    - "trade.created"       新成交
 *    - "account.update"      余额变化
 *    - "ledger.created"      账本新增
 *    - "liquidation.created" 强平
 *    - "margin.update"       margin 补充
 *    - "subscribed/unsubscribed/pong/error" 协议控制帧
 */
export function useTradingSocket(token: string | null, handlers: Handlers): TradingSocketState {
  const [state, setState] = useState<TradingSocketState>("idle");
  const handlersRef = useRef(handlers);
  useEffect(() => {
    handlersRef.current = handlers;
  }, [handlers]);
  // 2026-06-04 token 刷新竞态根治（与 useMarketSocket 同模式）：连接生命周期只挂
  // 「是否登录」，握手/重连时从 ref 读最新 token，15 分钟轮换不再重建连接。
  const tokenRef = useRef<string | null>(token);
  useEffect(() => {
    tokenRef.current = token;
  }, [token]);
  const hasToken = Boolean(token);

  /* eslint-disable react-hooks/set-state-in-effect */
  useEffect(() => {
    if (!hasToken) {
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
      // 握手时刻读最新 token（refresh 后 ref 已更新）
      const currentToken = tokenRef.current;
      if (!currentToken) {
        return;
      }
      setState(reconnectAttempt === 0 ? "connecting" : "reconnecting");
      const url = `${wsBaseUrl}/ws/v1/trading?token=${encodeURIComponent(currentToken)}`;
      socket = new WebSocket(url);

      socket.addEventListener("open", () => {
        reconnectAttempt = 0;
        setState("open");
        try {
          socket?.send(
            JSON.stringify({
              type: "subscribe",
              requestId: `sub-${Date.now()}`,
              channels: SUBSCRIBE_CHANNELS,
            })
          );
        } catch {
          // ignore send error
        }
      });

      socket.addEventListener("message", (event) => {
        if (typeof event.data !== "string") return;
        try {
          // 雪花 ID (positionId / orderId / tradeId 等) > 2^53；JSON.parse 直接吃 number 会丢低位精度。
          // 与 tradingApi.fetchTradingJson 同一守护：先用正则把这些字段包成字符串，再 parse。
          // 之前的 bug：XAUUSD 持仓 positionId 在 JSON 数字精度边界，REST 拉到的 ID 是精确的，
          // 但 WS 推过来的 number 被截断 → pnlMap.set(truncatedId, ...) 与
          // pnlMap.get(p.positionId) 的 key 失配 → 部分持仓收不到 PnL patch。
          const wrapped = event.data.replace(
            /("(?:positionId|orderId|tradeId|openingOrderId|parentPositionId|triggeredOrderId|id)":)\s*(\d{15,})/g,
            '$1"$2"',
          );
          const envelope = JSON.parse(wrapped) as {
            type?: string;
            channel?: string;
            data?: Record<string, unknown>;
          };
          switch (envelope.type) {
            case "order.update": {
              const d = envelope.data ?? {};
              const status = (d.status as string | undefined) ?? "";
              if (status === "REJECTED") {
                handlersRef.current.onOrderRejected?.(
                  (d.rejectReason as string | undefined) ?? null
                );
              } else if (status === "FILLED") {
                handlersRef.current.onOrderFilled?.({
                  orderNo: String(d.orderNo ?? d.id ?? ""),
                  symbol: String(d.symbol ?? ""),
                  side: String(d.side ?? ""),
                  filledQty: asNullableString(d.filledQty ?? d.quantity),
                  avgPrice: asNullableString(d.avgPrice ?? d.price),
                });
              }
              break;
            }
            case "position.pnl": {
              // STAGE-2-REALTIME-DATA Phase 1：PnL 增量；仅 patch UI，不触发 refetch
              const d = envelope.data ?? {};
              handlersRef.current.onPositionPnl?.({
                positionId: String(d.positionId ?? ""),
                symbol: String(d.symbol ?? ""),
                side: String(d.side ?? ""),
                markPrice: (d.markPrice as string | null | undefined) ?? null,
                quoteCurrency: (d.quoteCurrency as string | null | undefined) ?? null,
                fxRate: asNullableString(d.fxRate),
                unrealizedPnlInQuote: asNullableString(d.unrealizedPnlInQuote),
                unrealizedPnlInAccount: asNullableString(d.unrealizedPnlInAccount),
                isolatedMargin: asNullableString(d.isolatedMargin),
                liquidationDistance: (d.liquidationDistance as string | null | undefined) ?? null,
                quoteTs: (d.quoteTs as string | null | undefined) ?? null,
              });
              break;
            }
            case "user.position.summary": {
              const d = envelope.data ?? {};
              handlersRef.current.onPositionSummary?.({
                totalUnrealizedPnl: (d.totalUnrealizedPnl as string | null | undefined) ?? null,
                computedAt: (d.computedAt as string | null | undefined) ?? null,
              });
              break;
            }
            case "position.update": {
              const d = envelope.data ?? {};
              const status = (d.status as string | undefined) ?? "";
              if (status === "CLOSED" || status === "LIQUIDATED") {
                handlersRef.current.onPositionClosed?.({
                  positionId: String(d.positionId ?? d.id ?? ""),
                  symbol: String(d.symbol ?? ""),
                  side: String(d.side ?? ""),
                  status,
                  realizedPnl: asNullableString(d.realizedPnl ?? d.realizedPnL ?? d.pnl),
                  closePrice: asNullableString(d.closePrice ?? d.markPrice),
                  liquidationReason: asNullableString(d.liquidationReason ?? d.reason),
                });
              } else {
                // OPEN 持仓字段更新（TP/SL 改 / mark 更新），仅 refetch 不弹 toast。
                handlersRef.current.onOrderFilled?.();
              }
              break;
            }
            case "margin.update": {
              const d = envelope.data ?? {};
              handlersRef.current.onOrderFilled?.();
              handlersRef.current.onMarginAdded?.({
                positionId: String(d.positionId ?? d.id ?? ""),
                symbol: String(d.symbol ?? ""),
                amount: asNullableString(d.amount ?? d.delta ?? d.addedMargin),
              });
              break;
            }
            case "account.snapshot":
            case "account.update": {
              const d = envelope.data ?? {};
              handlersRef.current.onOrderFilled?.();
              handlersRef.current.onAccountBalanceChanged?.({
                currency: String(d.currency ?? "USDT"),
                balance: asNullableString(d.balance),
                available: asNullableString(d.available),
                delta: asNullableString(d.delta),
                reason: asNullableString(d.reason ?? d.changeType),
              });
              // STAGE-14E1：双币 + marginLevel 实时态。accountMarginMode 取后端账户级 marginMode，
              // 区别于本地下单偏好 preferencesStore.defaultMarginMode。
              handlersRef.current.onAccountUpdate?.({
                equity: asNullableString(d.equity),
                marginLevel: asNullableString(d.marginLevel),
                marginLevelStatus: (d.marginLevelStatus as string | null | undefined) ?? null,
                accountMarginMode: (d.marginMode as string | null | undefined) ?? null,
              });
              break;
            }
            case "trade.created":
            case "ledger.created":
            case "liquidation.created":
              // trade.created 与 order.update FILLED 重叠；
              // ledger/liquidation 由对应 order/position 事件已覆盖，仅 refetch。
              handlersRef.current.onOrderFilled?.();
              break;
            case "notification.created": {
              // STAGE-8-NOTIFICATION
              const d = envelope.data ?? {};
              handlersRef.current.onNotificationCreated?.({
                id: String(d.id ?? ""),
                type: String(d.type ?? ""),
                level: (String(d.level ?? "INFO") as "INFO" | "WARN" | "CRITICAL"),
                title: String(d.title ?? ""),
                body: String(d.body ?? ""),
                relatedKey: (d.relatedKey as string | null | undefined) ?? null,
                relatedId: (d.relatedId as string | null | undefined) ?? null,
                createdAt: String(d.createdAt ?? ""),
              });
              break;
            }
            case "price.alert.triggered": {
              // STAGE-4-PRICE-ALERT
              const d = envelope.data ?? {};
              handlersRef.current.onPriceAlertTriggered?.({
                alertId: String(d.alertId ?? ""),
                symbol: String(d.symbol ?? ""),
                direction: String(d.direction ?? "ABOVE") as "ABOVE" | "BELOW",
                targetPrice: String(d.targetPrice ?? ""),
                triggeredPrice: String(d.triggeredPrice ?? ""),
                triggerCount: Number(d.triggerCount ?? 0),
                remainingTriggers: Number(d.remainingTriggers ?? 0),
                exhausted: Boolean(d.exhausted),
                note: (d.note as string | null | undefined) ?? null,
                triggeredAt: String(d.triggeredAt ?? ""),
              });
              break;
            }
            case "subscribed":
            case "unsubscribed":
            case "pong":
            case "error":
              // 协议控制帧，无需处理
              break;
            default:
              break;
          }
        } catch {
          // 忽略解析失败
        }
      });

      socket.addEventListener("error", () => setState("error"));

      socket.addEventListener("close", (event) => {
        if (!shouldReconnect) {
          setState("closed");
          return;
        }
        if (event.code === 4001) {
          shouldReconnect = false;
          setState("closed");
          notifyAuthExpired();
          return;
        }
        const delay = Math.min(BASE_RECONNECT_DELAY_MS * 2 ** reconnectAttempt, MAX_RECONNECT_DELAY_MS);
        reconnectAttempt += 1;
        reconnectTimer = setTimeout(connect, delay);
      });
    };

    // 延一个 macro task 再建连：StrictMode 开发模式下 mount→unmount→mount，
    // 不延迟会在握手中被 cleanup close，浏览器报 "WebSocket is closed before the connection is established"。
    const startTimer = setTimeout(connect, 0);

    return () => {
      shouldReconnect = false;
      clearTimeout(startTimer);
      clearReconnectTimer();
      socket?.close(1000);
    };
  }, [hasToken]);
  /* eslint-enable react-hooks/set-state-in-effect */

  return state;
}

function asNullableString(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === "string") return value.length > 0 ? value : null;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
}
