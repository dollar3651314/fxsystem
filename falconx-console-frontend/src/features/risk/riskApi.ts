import { adminApi } from "../../lib/api/apiClient";
import type {
  RiskActionActivateRequest,
  RiskActionItem,
  RiskActionListQuery,
  RiskActionListResponse,
  RiskConfigCreateRequest,
  RiskConfigItem,
  RiskConfigListQuery,
  RiskConfigListResponse,
  RiskConfigUpdateRequest,
  RiskMarketConfigItem,
  RiskMarketConfigListResponse,
  RiskMarketConfigUpdateRequest,
  // BBOOK-RISK-CONTROL-01
  PlatformRiskConfigUpdateRequest,
  DirectionImbalanceUpdateRequest,
  UserRiskThresholdItem,
  UserRiskThresholdListResponse,
  UserRiskThresholdUpsertRequest,
} from "./types";

function buildActionQuery(q: RiskActionListQuery): string {
  const params = new URLSearchParams();
  if (q.symbol) params.set("symbol", q.symbol);
  if (q.actionType) params.set("actionType", q.actionType);
  if (q.triggerSource) params.set("triggerSource", q.triggerSource);
  if (q.isActive != null) params.set("isActive", String(q.isActive));
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

function buildConfigQuery(q: RiskConfigListQuery): string {
  const params = new URLSearchParams();
  if (q.symbol) params.set("symbol", q.symbol);
  if (q.marketCode) params.set("marketCode", q.marketCode);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const riskApi = {
  listActions: (q: RiskActionListQuery) =>
    adminApi.get<RiskActionListResponse>(`/admin/risk-actions?${buildActionQuery(q)}`),
  activateAction: (req: RiskActionActivateRequest) =>
    adminApi.post<RiskActionItem>(`/admin/risk-actions`, req),
  deactivateAction: (id: string, reason: string) =>
    adminApi.post<RiskActionItem>(`/admin/risk-actions/${id}/deactivate`, { reason }),

  listConfigs: (q: RiskConfigListQuery) =>
    adminApi.get<RiskConfigListResponse>(`/admin/risk-configs?${buildConfigQuery(q)}`),
  createConfig: (req: RiskConfigCreateRequest) =>
    adminApi.post<RiskConfigItem>(`/admin/risk-configs`, req),
  updateConfig: (symbol: string, req: RiskConfigUpdateRequest) =>
    adminApi.put<RiskConfigItem>(`/admin/risk-configs/${symbol}`, req),
  deleteConfig: (symbol: string, reason: string) =>
    adminApi.delete<void>(`/admin/risk-configs/${symbol}`, { reason }),

  listMarketConfigs: () =>
    adminApi.get<RiskMarketConfigListResponse>(`/admin/risk-market-configs`),
  updateMarketConfig: (marketCode: string, req: RiskMarketConfigUpdateRequest) =>
    adminApi.put<RiskMarketConfigItem>(`/admin/risk-market-configs/${marketCode}`, req),

  // BBOOK-RISK-CONTROL-01：平台 + 方向集中度 + 用户阈值
  updatePlatformRiskConfig: (req: PlatformRiskConfigUpdateRequest) =>
    adminApi.post<void>(`/admin/risk-config/platform`, req),
  updateDirectionImbalance: (symbol: string, req: DirectionImbalanceUpdateRequest) =>
    adminApi.post<void>(`/admin/risk-config/${symbol}/direction-imbalance`, req),

  listUserRiskThresholds: (userId: number | undefined, page: number, size: number) => {
    const params = new URLSearchParams();
    if (userId != null) params.set("userId", String(userId));
    params.set("page", String(page));
    params.set("size", String(size));
    return adminApi.get<UserRiskThresholdListResponse>(`/admin/user-risk-thresholds?${params.toString()}`);
  },
  upsertUserRiskThreshold: (req: UserRiskThresholdUpsertRequest) =>
    adminApi.post<UserRiskThresholdItem>(`/admin/user-risk-thresholds`, req),
  deleteUserRiskThreshold: (userId: number) =>
    adminApi.delete<void>(`/admin/user-risk-thresholds/${userId}`),
};
