import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminReconciliationListQuery,
  AdminReconciliationListResponse,
  AdminReconciliationMarkResolvedRequest,
  AdminReconciliationMarkResolvedResponse,
  SnowflakeId,
} from "./types";

function buildQuery(q: AdminReconciliationListQuery): string {
  const params = new URLSearchParams();
  if (q.chain) params.set("chain", q.chain);
  if (q.token) params.set("token", q.token);
  if (q.discrepancyType) params.set("discrepancyType", q.discrepancyType);
  if (q.fromDetectedAt) params.set("fromDetectedAt", q.fromDetectedAt);
  if (q.toDetectedAt) params.set("toDetectedAt", q.toDetectedAt);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

/**
 * STAGE-11-OBS-RECON §14 入金对账 admin API client。
 *
 * - GET unmatched 列表（RBAC reconciliation:view）
 * - POST mark-resolved（RBAC reconciliation:resolve，高危）
 */
export const reconciliationApi = {
  listUnmatched: (q: AdminReconciliationListQuery) =>
    adminApi.get<AdminReconciliationListResponse>(
      `/admin/reconciliation/deposits/unmatched?${buildQuery(q)}`,
    ),

  markResolved: (walletTxId: SnowflakeId, req: AdminReconciliationMarkResolvedRequest) =>
    adminApi.post<AdminReconciliationMarkResolvedResponse>(
      `/admin/reconciliation/deposits/${walletTxId}/mark-resolved`,
      req,
    ),
};
