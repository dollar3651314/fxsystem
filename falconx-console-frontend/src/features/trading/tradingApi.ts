import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminPlatformMetrics,
  AdminSwapSummary,
  ExposureListResponse,
  ManualLiquidateResponse,
  OrderListQuery,
  OrderListResponse,
  PositionListQuery,
  PositionListResponse,
  PositionSummary,
  RiskSwitchListResponse,
  RiskSwitchUpdateResponse,
  SnowflakeId,
  AdminPendingOrderListResponse,
  AdminPendingOrderListQuery,
  AdminPendingOrderItem,
  AdminPriceAlertListResponse,
  AdminPriceAlertListQuery,
  AdminPriceAlertItem,
} from "./types";

function buildOrderQuery(q: OrderListQuery): string {
  const params = new URLSearchParams();
  if (q.userId != null) params.set("userId", String(q.userId));
  if (q.symbol) params.set("symbol", q.symbol);
  if (q.status != null) params.set("status", String(q.status));
  if (q.fromCreatedAt) params.set("fromCreatedAt", q.fromCreatedAt);
  if (q.toCreatedAt) params.set("toCreatedAt", q.toCreatedAt);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

function buildPositionQuery(q: PositionListQuery): string {
  const params = new URLSearchParams();
  if (q.userId != null) params.set("userId", String(q.userId));
  if (q.symbol) params.set("symbol", q.symbol);
  if (q.status != null) params.set("status", String(q.status));
  if (q.fromOpenedAt) params.set("fromOpenedAt", q.fromOpenedAt);
  if (q.toOpenedAt) params.set("toOpenedAt", q.toOpenedAt);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const tradingApi = {
  listOrders: (query: OrderListQuery) =>
    adminApi.get<OrderListResponse>(`/admin/trading/orders?${buildOrderQuery(query)}`),

  listPositions: (query: PositionListQuery) =>
    adminApi.get<PositionListResponse>(`/admin/trading/positions?${buildPositionQuery(query)}`),

  getPositionSummary: () =>
    adminApi.get<PositionSummary>("/admin/trading/positions/summary"),

  getPositionSwapSummary: (positionId: SnowflakeId) =>
    adminApi.get<AdminSwapSummary>(`/admin/trading/positions/${positionId}/swap-summary`),

  getPlatformMetricsOverview: () =>
    adminApi.get<AdminPlatformMetrics>("/admin/trading/platform/metrics-overview"),

  listExposures: (symbol?: string) =>
    adminApi.get<ExposureListResponse>(
      symbol ? `/admin/trading/exposures?symbol=${encodeURIComponent(symbol)}` : "/admin/trading/exposures"
    ),

  listRiskSwitches: () =>
    adminApi.get<RiskSwitchListResponse>("/admin/trading/risk-switches"),

  manualLiquidate: (positionId: SnowflakeId, reason: string) =>
    adminApi.post<ManualLiquidateResponse>(
      `/admin/trading/positions/${positionId}/manual-liquidate`,
      { reason }
    ),

  updateAutoLiquidateSwitch: (enabled: boolean, reason: string) =>
    adminApi.post<RiskSwitchUpdateResponse>(
      "/admin/trading/risk-switches/auto-liquidate",
      { enabled, reason }
    ),

  // STAGE-3-PENDING-ORDER
  // 雪花 ID > 2^53 → JSON.parse 精度损失（如 47939213290770432 → 47939213290770430）
  // 手动 fetch + 正则把 id / parentPositionId / triggeredOrderId 转字符串
  listPendingOrders: async (q: AdminPendingOrderListQuery): Promise<AdminPendingOrderListResponse> => {
    const params = new URLSearchParams();
    if (q.userId != null) params.set("userId", String(q.userId));
    if (q.symbol) params.set("symbol", q.symbol);
    if (q.status != null) params.set("status", String(q.status));
    if (q.includeSlTp != null) params.set("includeSlTp", String(q.includeSlTp));
    params.set("page", String(q.page ?? 1));
    params.set("size", String(q.size ?? 20));
    const { getAdminAccessToken } = await import("../../lib/auth/adminTokenStorage");
    const token = getAdminAccessToken();
    const resp = await fetch(`/admin/trading/pending-orders?${params.toString()}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const raw = await resp.text();
    const wrapped = raw.replace(/("(?:id|triggeredOrderId|parentPositionId)":)\s*(\d{15,})/g, '$1"$2"');
    const parsed = JSON.parse(wrapped) as { code: string; data: AdminPendingOrderListResponse; message?: string };
    if (parsed.code !== "0") throw new Error(parsed.message ?? "Unknown error");
    return parsed.data;
  },

  cancelPendingOrder: (id: string, reason: string) =>
    adminApi.post<AdminPendingOrderItem>(`/admin/trading/pending-orders/${id}/cancel`, { reason }),

  // STAGE-4-PRICE-ALERT
  listPriceAlerts: (q: AdminPriceAlertListQuery) => {
    const params = new URLSearchParams();
    if (q.userId != null) params.set("userId", String(q.userId));
    if (q.symbol) params.set("symbol", q.symbol);
    if (q.status != null) params.set("status", String(q.status));
    params.set("page", String(q.page ?? 1));
    params.set("size", String(q.size ?? 20));
    return adminApi.get<AdminPriceAlertListResponse>(`/admin/trading/price-alerts?${params.toString()}`);
  },

  deletePriceAlert: (id: string, reason: string) =>
    adminApi.post<AdminPriceAlertItem>(`/admin/trading/price-alerts/${id}/delete`, { reason }),
};
