import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminPermissionListQuery,
  AdminPermissionListResponse,
  AdminPermissionRoleRef,
} from "./types";

function buildListQuery(q: AdminPermissionListQuery): string {
  const params = new URLSearchParams();
  if (q.module) params.set("module", q.module);
  if (q.action) params.set("action", q.action);
  if (q.highRiskOnly !== undefined) params.set("highRiskOnly", String(q.highRiskOnly));
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 100));
  return params.toString();
}

export const adminPermissionsApi = {
  list: (q: AdminPermissionListQuery) =>
    adminApi.get<AdminPermissionListResponse>(`/admin/admin-permissions?${buildListQuery(q)}`),

  roles: (code: string) =>
    adminApi.get<AdminPermissionRoleRef[]>(`/admin/admin-permissions/${encodeURIComponent(code)}/roles`),
};
