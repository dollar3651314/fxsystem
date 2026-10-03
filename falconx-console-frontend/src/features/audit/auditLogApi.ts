import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminAuditLogItem,
  AdminAuditLogListQuery,
  AdminAuditLogListResponse,
  SnowflakeId,
} from "./types";

function buildQuery(q: AdminAuditLogListQuery): string {
  const params = new URLSearchParams();
  if (q.adminUserId) params.set("adminUserId", q.adminUserId);
  if (q.permissionCode) params.set("permissionCode", q.permissionCode);
  if (q.targetType) params.set("targetType", q.targetType);
  if (q.targetId) params.set("targetId", q.targetId);
  if (q.riskLevel) params.set("riskLevel", q.riskLevel);
  if (q.fromOccurredAt) params.set("fromOccurredAt", q.fromOccurredAt);
  if (q.toOccurredAt) params.set("toOccurredAt", q.toOccurredAt);
  params.set("page", String(q.page ?? 1));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

/**
 * STAGE-9-RISK-OPS-COMPLETE §12.2 审计日志查询 admin API client。
 *
 * 2 个端点对齐管理端接口规范 §13：
 * - GET /admin/audit-logs：7 个可选过滤 + 分页
 * - GET /admin/audit-logs/{id}：单条详情
 */
export const auditLogApi = {
  list: (q: AdminAuditLogListQuery) =>
    adminApi.get<AdminAuditLogListResponse>(`/admin/audit-logs?${buildQuery(q)}`),

  detail: (id: SnowflakeId) =>
    adminApi.get<AdminAuditLogItem>(`/admin/audit-logs/${id}`),
};
