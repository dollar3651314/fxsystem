import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminUserCreateRequest,
  AdminUserCreateResponse,
  AdminUserListItem,
  AdminUserListQuery,
  AdminUserListResponse,
  AdminUserResetPasswordRequest,
  AdminUserResetPasswordResponse,
  AdminUserUpdateRequest,
  SnowflakeId,
} from "./types";

function buildListQuery(q: AdminUserListQuery): string {
  const params = new URLSearchParams();
  if (q.username) params.set("username", q.username);
  if (q.realName) params.set("realName", q.realName);
  if (q.status) params.set("status", q.status);
  if (q.roleCode) params.set("roleCode", q.roleCode);
  if (q.from) params.set("from", q.from);
  if (q.to) params.set("to", q.to);
  params.set("page", String(q.page ?? 0));
  params.set("size", String(q.size ?? 20));
  return params.toString();
}

export const adminUsersApi = {
  list: (q: AdminUserListQuery) =>
    adminApi.get<AdminUserListResponse>(`/admin/admin-users?${buildListQuery(q)}`),

  detail: (id: SnowflakeId) =>
    adminApi.get<AdminUserListItem>(`/admin/admin-users/${id}`),

  create: (body: AdminUserCreateRequest) =>
    adminApi.post<AdminUserCreateResponse>(`/admin/admin-users`, body),

  update: (id: SnowflakeId, body: AdminUserUpdateRequest) =>
    adminApi.put<AdminUserListItem>(`/admin/admin-users/${id}`, body),

  disable: (id: SnowflakeId, reason: string) =>
    adminApi.post<void>(`/admin/admin-users/${id}/disable`, { reason }),

  enable: (id: SnowflakeId) =>
    adminApi.post<void>(`/admin/admin-users/${id}/enable`, {}),

  resetPassword: (id: SnowflakeId, body: AdminUserResetPasswordRequest) =>
    adminApi.post<AdminUserResetPasswordResponse>(`/admin/admin-users/${id}/reset-password`, body),

  remove: (id: SnowflakeId) =>
    adminApi.delete<void>(`/admin/admin-users/${id}`),
};
