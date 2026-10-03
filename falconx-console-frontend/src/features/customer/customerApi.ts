import { adminApi } from "../../lib/api/apiClient";
import type {
  BalanceAdjustResponse,
  CustomerDetail,
  CustomerListQuery,
  CustomerListResponse,
  CustomerPatchRequest,
  FreezeUnfreezeResponse,
  SnowflakeId,
} from "./types";

function buildListQuery(query: CustomerListQuery): string {
  const params = new URLSearchParams();
  if (query.email) params.set("email", query.email);
  if (query.status && query.status.length > 0) params.set("status", query.status.join(","));
  if (query.from) params.set("from", query.from);
  if (query.to) params.set("to", query.to);
  params.set("page", String(query.page ?? 0));
  params.set("size", String(query.size ?? 20));
  return params.toString();
}

export const customerApi = {
  list: (query: CustomerListQuery) =>
    adminApi.get<CustomerListResponse>(`/admin/customers?${buildListQuery(query)}`),

  detail: (userId: SnowflakeId) =>
    adminApi.get<CustomerDetail>(`/admin/customers/${userId}`),

  freeze: (userId: SnowflakeId, reason: string) =>
    adminApi.post<FreezeUnfreezeResponse>(`/admin/customers/${userId}/freeze`, { reason }),

  unfreeze: (userId: SnowflakeId, reason: string) =>
    adminApi.post<FreezeUnfreezeResponse>(`/admin/customers/${userId}/unfreeze`, { reason }),

  adjustBalance: (userId: SnowflakeId, deltaUSD: string, reason: string) =>
    adminApi.post<BalanceAdjustResponse>(`/admin/customers/${userId}/balance/adjust`, {
      deltaUSD,
      reason,
    }),

  /** PATCH /admin/customers/{userId}：编辑客户 identity 元数据 + profile 资料；reason 必填。 */
  edit: (userId: SnowflakeId, request: CustomerPatchRequest) =>
    adminApi.patch<void>(`/admin/customers/${userId}`, request),
};
