import { requestJson } from "../../lib/api";
import type { LeverageTierListResponse } from "./leverageTiers";
import type {
  AddMarginRequest,
  ClosePositionResponse,
  OrderItem,
  PaginatedResponse,
  PlaceMarketOrderRequest,
  PlaceMarketOrderResponse,
  PositionItem,
  TradeItem,
  UpdateRiskControlsRequest,
  PlaceLimitOrderRequest as PlaceLimitOrderRequestT,
  PlaceStopOrderRequest as PlaceStopOrderRequestT,
  ModifyPendingOrderRequest as ModifyPendingOrderRequestT,
  PendingOrderItem,
  CreatePriceAlertRequest as CreatePriceAlertRequestT,
  PriceAlertItem,
  NotificationListResponse,
} from "./tradingTypes";

export function placeMarketOrder(token: string, request: PlaceMarketOrderRequest): Promise<PlaceMarketOrderResponse> {
  return requestJson<PlaceMarketOrderResponse>("/api/v1/trading/orders/market", {
    method: "POST",
    token,
    body: JSON.stringify(request),
  });
}

// 雪花 ID >2^53 在 JSON.parse 内会被截成最近的安全整数（47989177408688130 → 47989177408688128）。
// 在文本层把 positionId / openingOrderId / orderId / tradeId 这些 ID 字段强转 string，
// 再 JSON.parse；后端路径直接拼字符串即可。
const ID_FIELDS_RE = /("(?:positionId|openingOrderId|orderId|tradeId|parentPositionId|triggeredOrderId)":)\s*(\d{15,})/g;

async function fetchTradingJson<T>(path: string, token: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, ...(init?.headers ?? {}) },
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const raw = await resp.text();
  const wrapped = raw.replace(ID_FIELDS_RE, '$1"$2"');
  const parsed = JSON.parse(wrapped) as { code: string; data: T; message?: string };
  if (parsed.code !== "0") throw new Error(parsed.message ?? "Unknown error");
  return parsed.data;
}

/**
 * status: 可选过滤，单值如 "OPEN" 或 逗号分隔如 "CLOSED,LIQUIDATED"，不传则返回全部状态
 */
export function listPositions(
  token: string,
  page = 1,
  pageSize = 20,
  status?: string,
): Promise<PaginatedResponse<PositionItem>> {
  const params = new URLSearchParams();
  params.set("page", String(page));
  params.set("pageSize", String(pageSize));
  if (status) params.set("status", status);
  return fetchTradingJson<PaginatedResponse<PositionItem>>(
    `/api/v1/trading/positions?${params.toString()}`,
    token,
  );
}

export function listOrders(token: string, page = 1, pageSize = 20): Promise<PaginatedResponse<OrderItem>> {
  return fetchTradingJson<PaginatedResponse<OrderItem>>(
    `/api/v1/trading/orders?page=${page}&pageSize=${pageSize}`,
    token
  );
}

export function listTrades(token: string, page = 1, pageSize = 20): Promise<PaginatedResponse<TradeItem>> {
  return fetchTradingJson<PaginatedResponse<TradeItem>>(
    `/api/v1/trading/trades?page=${page}&pageSize=${pageSize}`,
    token
  );
}

export function closePosition(token: string, positionId: string): Promise<ClosePositionResponse> {
  return fetchTradingJson<ClosePositionResponse>(
    `/api/v1/trading/positions/${positionId}/close`,
    token,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: "{}" }
  );
}

export function updateRiskControls(
  token: string,
  positionId: string,
  request: UpdateRiskControlsRequest
): Promise<PositionItem> {
  // 后端 PATCH 通过 Map<String, Object> 解析，期望 takeProfitPrice/stopLossPrice 为 number 或 null
  const body: Record<string, number | null> = {};
  if (request.takeProfitPrice !== undefined) {
    body.takeProfitPrice = request.takeProfitPrice === null ? null : Number(request.takeProfitPrice);
  }
  if (request.stopLossPrice !== undefined) {
    body.stopLossPrice = request.stopLossPrice === null ? null : Number(request.stopLossPrice);
  }
  return fetchTradingJson<PositionItem>(
    `/api/v1/trading/positions/${positionId}`,
    token,
    { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
  );
}

export function addMargin(
  token: string,
  positionId: string,
  request: AddMarginRequest
): Promise<PositionItem> {
  return fetchTradingJson<PositionItem>(
    `/api/v1/trading/positions/${positionId}/margin`,
    token,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ amount: Number(request.amount) }) }
  );
}

export function newClientOrderId(): string {
  return `cli-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

export interface TradingAccountResponse {
  accountId: number;
  userId: number;
  currency: string;
  balance: string;
  frozen: string;
  marginUsed: string;
  available: string;
  marginMode: string;
  /** STAGE-14E1: 账户权益 = balance + 全部持仓未实现盈亏（账户币口径）。 */
  equity?: string | null;
  /** STAGE-14E1: 保证金率（equity / 维持保证金等口径），null = 无持仓或后端未算。 */
  marginLevel?: string | null;
  /** STAGE-14E1: 保证金率健康度：HEALTHY / MARGIN_CALL / STOP_OUT。 */
  marginLevelStatus?: string | null;
  // STAGE-14E1 Task7（Task4 遗留清理）：后端 account snapshot 含双币 openPositions[]，
  // 但客户端不从 /accounts/me 消费持仓 —— 持仓走独立的 listPositions() query（dashboard /
  // PositionsTable 均如此）。故此处不声明 openPositions 字段，避免引入未消费且易随后端漂移的
  // 单/双币类型。若将来客户端改为消费 account.openPositions，须按 PositionItem 双币口径
  // （quoteCurrency / unrealizedPnlInQuote / unrealizedPnlInAccount / isolatedMargin）声明，
  // 切勿沿用旧单币 unrealizedPnl。
}

/**
 * B 切片（2026-06-03）：杠杆/MM 档位查询——下单面板按名义价值动态降档用。
 * 档位按调用方用户组解析（gateway 注入 X-User-Group-Code），与开仓风控同源。
 */
export function getLeverageTiers(token: string, symbol: string): Promise<LeverageTierListResponse> {
  return requestJson<LeverageTierListResponse>(
    `/api/v1/trading/symbols/${encodeURIComponent(symbol)}/leverage-tiers`,
    { token }
  );
}

export function getAccount(token: string): Promise<TradingAccountResponse> {
  return requestJson<TradingAccountResponse>("/api/v1/trading/accounts/me", { token });
}

export interface PositionSummaryResponse {
  openPositionCount: number;
  totalMarginUsed: string;
  totalUnrealizedPnl: string;
  positionsWithoutQuote: number;
  computedAt: string;
}

/**
 * 用户持仓汇总：OPEN 笔数 / 总占用保证金 / 总未实现盈亏。
 * 用于 dashboard / market 首次渲染拿初值，之后由 WS user.position.summary 实时刷。
 */
export function getPositionSummary(token: string): Promise<PositionSummaryResponse> {
  return requestJson<PositionSummaryResponse>("/api/v1/trading/positions/summary", { token });
}

// Swap 资金费率聚合（持仓维度 + 账户维度）
export interface SwapSummary {
  totalCharge: string;
  totalIncome: string;
  net: string;
  chargeCount: number;
  incomeCount: number;
  firstAt: string | null;
  lastAt: string | null;
}

export function getPositionSwapSummary(token: string, positionId: string): Promise<SwapSummary> {
  return requestJson<SwapSummary>(`/api/v1/trading/positions/${positionId}/swap-summary`, { token });
}

export function getAccountSwapSummary(token: string, days = 30): Promise<SwapSummary> {
  return requestJson<SwapSummary>(`/api/v1/trading/account/swap-summary?days=${days}`, { token });
}

// STAGE-14E1 Task5：账户级 margin mode（对接 D1 /api/v1/me/margin-mode）。
// X-User-Id 由 gateway 注入；客户端只带 Bearer token（同 getAccount 口径），不传 userId。

export type AccountMarginMode = "ISOLATED" | "CROSS";

export interface MarginModeResponse {
  /** 当前账户 margin mode（区分本地偏好 defaultMarginMode）。 */
  currentMode: AccountMarginMode;
  /** 上次切换时间，可为 null（从未切换过）。 */
  modeChangedAt: string | null;
  /** 冷静期截止时间，null 表示不在冷静期。 */
  coolingUntil: string | null;
  /** 当前是否可发起切换（任一阻断项存在即 false）。 */
  canSwitch: boolean;
  /** 阻断原因列表：OPEN_POSITIONS / ACTIVE_PENDING / COOLING。 */
  blockers: string[];
  /** 平台是否开放 CROSS（全仓）模式。false = 全仓暂未开放，前端据此主动 disable CROSS。 */
  crossModeEnabled: boolean;
}

export function getMarginMode(token: string): Promise<MarginModeResponse> {
  return requestJson<MarginModeResponse>("/api/v1/me/margin-mode", { token });
}

export function setMarginMode(token: string, targetMode: AccountMarginMode): Promise<MarginModeSwitchResult> {
  return requestJson<MarginModeSwitchResult>("/api/v1/me/margin-mode", {
    method: "POST",
    token,
    body: JSON.stringify({ targetMode }),
  });
}

export interface MarginModeSwitchResult {
  oldMode: AccountMarginMode;
  newMode: AccountMarginMode;
  modeChangedAt: string;
  coolingUntil: string | null;
}

// STAGE-3-PENDING-ORDER：挂单 API

export function placeLimitOrder(token: string, req: PlaceLimitOrderRequestT): Promise<PendingOrderItem> {
  return requestJson<PendingOrderItem>("/api/v1/trading/orders/limit", {
    method: "POST",
    token,
    body: JSON.stringify(req),
  });
}

export function placeStopOrder(token: string, req: PlaceStopOrderRequestT): Promise<PendingOrderItem> {
  return requestJson<PendingOrderItem>("/api/v1/trading/orders/stop", {
    method: "POST",
    token,
    body: JSON.stringify(req),
  });
}

/**
 * 雪花 ID 超 2^53，JSON.parse 会精度损失（如 47938339441086464 → 47938339441086460）。
 * 用正则在 JSON 文本层面把 `"id": <bigInt>` 转成 `"id": "<bigInt>"`，
 * 然后再 JSON.parse。
 */
export async function listPendingOrders(token: string, page = 1, pageSize = 50): Promise<PaginatedResponse<PendingOrderItem>> {
  const resp = await fetch(`/api/v1/trading/orders/pending?page=${page}&pageSize=${pageSize}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const raw = await resp.text();
  // 把 "id":47938339441086464 → "id":"47938339441086464"（同样处理 triggeredOrderId 等大整数）
  const wrapped = raw.replace(/("(?:id|triggeredOrderId|parentPositionId)":)\s*(\d{15,})/g, '$1"$2"');
  const parsed = JSON.parse(wrapped) as { code: string; data: PaginatedResponse<PendingOrderItem>; message?: string };
  if (parsed.code !== "0") throw new Error(parsed.message ?? "Unknown error");
  return parsed.data;
}

export function cancelPendingOrder(token: string, id: string): Promise<PendingOrderItem> {
  return requestJson<PendingOrderItem>(`/api/v1/trading/orders/${id}`, {
    method: "DELETE",
    token,
  });
}

export function modifyPendingOrder(token: string, id: string, req: ModifyPendingOrderRequestT): Promise<PendingOrderItem> {
  return requestJson<PendingOrderItem>(`/api/v1/trading/orders/${id}`, {
    method: "PATCH",
    token,
    body: JSON.stringify(req),
  });
}

// STAGE-4-PRICE-ALERT：价格告警 API

export function createPriceAlert(token: string, req: CreatePriceAlertRequestT): Promise<PriceAlertItem> {
  return requestJson<PriceAlertItem>("/api/v1/trading/price-alerts", {
    method: "POST",
    token,
    body: JSON.stringify(req),
  });
}

/**
 * 雪花 ID 用 string 避免 JSON.parse 精度损失（同 PendingOrder 处理方式）
 */
export async function listPriceAlerts(token: string, status?: number, symbol?: string,
                                       page = 1, pageSize = 50): Promise<PaginatedResponse<PriceAlertItem>> {
  const params = new URLSearchParams();
  if (status != null) params.set("status", String(status));
  if (symbol) params.set("symbol", symbol);
  params.set("page", String(page));
  params.set("pageSize", String(pageSize));
  const resp = await fetch(`/api/v1/trading/price-alerts?${params.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  const raw = await resp.text();
  // 后端已经返回 id 为 string，但保险起见仍 wrap（与 PendingOrder 同口径）
  const parsed = JSON.parse(raw) as { code: string; data: PaginatedResponse<PriceAlertItem>; message?: string };
  if (parsed.code !== "0") throw new Error(parsed.message ?? "Unknown error");
  return parsed.data;
}

export function cancelPriceAlert(token: string, id: string): Promise<PriceAlertItem> {
  return requestJson<PriceAlertItem>(`/api/v1/trading/price-alerts/${id}`, {
    method: "DELETE",
    token,
  });
}

// STAGE-8-NOTIFICATION：站内信 API

export function listNotifications(token: string, page = 1, pageSize = 30): Promise<NotificationListResponse> {
  return requestJson<NotificationListResponse>(
    `/api/v1/trading/notifications?page=${page}&pageSize=${pageSize}`,
    { token }
  );
}

export function getNotificationUnreadCount(token: string): Promise<{ unread: number }> {
  return requestJson<{ unread: number }>(`/api/v1/trading/notifications/unread-count`, { token });
}

export function markNotificationRead(token: string, id: string): Promise<{ updated: boolean; unread: number }> {
  return requestJson<{ updated: boolean; unread: number }>(
    `/api/v1/trading/notifications/${id}/read`,
    { method: "POST", token, body: "{}" }
  );
}

export function markAllNotificationsRead(token: string): Promise<{ updated: number; unread: number }> {
  return requestJson<{ updated: number; unread: number }>(
    `/api/v1/trading/notifications/read-all`,
    { method: "POST", token, body: "{}" }
  );
}
