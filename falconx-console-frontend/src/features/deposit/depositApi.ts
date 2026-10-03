import { adminApi } from "../../lib/api/apiClient";
import type {
  DepositItem,
  DepositListQuery,
  DepositListResponse,
  SnowflakeId,
} from "./types";

function buildQuery(q: DepositListQuery): string {
  const params = new URLSearchParams();
  if (q.userId != null) params.set("userId", String(q.userId));
  if (q.chain) params.set("chain", q.chain);
  if (q.token) params.set("token", q.token);
  if (q.status && q.status.length > 0) params.set("status", q.status.join(","));
  if (q.fromDetectedAt) params.set("fromDetectedAt", q.fromDetectedAt);
  if (q.toDetectedAt) params.set("toDetectedAt", q.toDetectedAt);
  if (q.onlyOrphan) params.set("onlyOrphan", "true");
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const depositApi = {
  list: (q: DepositListQuery) =>
    adminApi.get<DepositListResponse>(`/admin/deposits?${buildQuery(q)}`),
  detail: (id: SnowflakeId) =>
    adminApi.get<DepositItem>(`/admin/deposits/${id}`),
};
