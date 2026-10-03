import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminRoleAssignPermissionsRequest,
  AdminRoleCreateRequest,
  AdminRoleDetail,
  AdminRoleListQuery,
  AdminRoleListResponse,
  AdminRolePermissionsResponse,
  AdminRoleUpdateRequest,
  SnowflakeId,
} from "./types";

function buildListQuery(q: AdminRoleListQuery): string {
  const params = new URLSearchParams();
  if (q.code) params.set("code", q.code);
  if (q.name) params.set("name", q.name);
  if (q.isSystem !== undefined) params.set("isSystem", String(q.isSystem));
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const adminRolesApi = {
  list: (q: AdminRoleListQuery) =>
    adminApi.get<AdminRoleListResponse>(`/admin/admin-roles?${buildListQuery(q)}`),

  create: (body: AdminRoleCreateRequest) =>
    adminApi.post<AdminRoleDetail>(`/admin/admin-roles`, body),

  update: (id: SnowflakeId, body: AdminRoleUpdateRequest) =>
    adminApi.put<AdminRoleDetail>(`/admin/admin-roles/${id}`, body),

  remove: (id: SnowflakeId) =>
    adminApi.delete<void>(`/admin/admin-roles/${id}`),

  getPermissions: (id: SnowflakeId) =>
    adminApi.get<AdminRolePermissionsResponse>(`/admin/admin-roles/${id}/permissions`),

  assignPermissions: (id: SnowflakeId, body: AdminRoleAssignPermissionsRequest) =>
    adminApi.put<AdminRolePermissionsResponse>(`/admin/admin-roles/${id}/permissions`, body),
};
