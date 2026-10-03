import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminWithdrawItem,
  AdminWithdrawListQuery,
  AdminWithdrawListResponse,
  SnowflakeId,
} from "./types";

function buildQuery(q: AdminWithdrawListQuery): string {
  const params = new URLSearchParams();
  if (q.status) params.set("status", q.status);
  if (q.userId) params.set("userId", q.userId);
  if (q.network) params.set("network", q.network);
  if (q.minAmount) params.set("minAmount", q.minAmount);
  if (q.maxAmount) params.set("maxAmount", q.maxAmount);
  params.set("page", String(q.page ?? 1));
  params.set("pageSize", String(q.pageSize ?? 20));
  return params.toString();
}

/**
 * STAGE-7-WITHDRAW Phase 4 出金审核 admin API client。
 *
 * 5 端点对齐管理端接口规范 §10：
 * - GET  /admin/withdraws
 * - GET  /admin/withdraws/:id
 * - POST /admin/withdraws/:id/approve     body: { reviewNote? }
 * - POST /admin/withdraws/:id/reject      body: { reason }      reason 必填且 5+ 字符（前端 modal 已校验）
 * - POST /admin/withdraws/:id/emergency-cancel  body: { reason } 同上
 */
export const withdrawApi = {
  list: (q: AdminWithdrawListQuery) =>
    adminApi.get<AdminWithdrawListResponse>(`/admin/withdraws?${buildQuery(q)}`),

  detail: (id: SnowflakeId) =>
    adminApi.get<AdminWithdrawItem>(`/admin/withdraws/${id}`),

  approve: (id: SnowflakeId, reviewNote?: string) =>
    adminApi.post<AdminWithdrawItem>(
      `/admin/withdraws/${id}/approve`,
      reviewNote && reviewNote.trim() ? { reviewNote: reviewNote.trim() } : {},
    ),

  reject: (id: SnowflakeId, reason: string) =>
    adminApi.post<AdminWithdrawItem>(`/admin/withdraws/${id}/reject`, { reason }),

  emergencyCancel: (id: SnowflakeId, reason: string) =>
    adminApi.post<AdminWithdrawItem>(`/admin/withdraws/${id}/emergency-cancel`, { reason }),
};
