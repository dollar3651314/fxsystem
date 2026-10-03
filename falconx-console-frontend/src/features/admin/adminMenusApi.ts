import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminMenuCreateRequest,
  AdminMenuItem,
  AdminMenuListResponse,
  AdminMenuSortDirection,
  AdminMenuUpdateRequest,
  SnowflakeId,
} from "./types";

export const adminMenusApi = {
  list: (tree: boolean = true) =>
    adminApi.get<AdminMenuListResponse>(`/admin/admin-menus?tree=${tree}`),

  create: (body: AdminMenuCreateRequest) =>
    adminApi.post<AdminMenuItem>(`/admin/admin-menus`, body),

  update: (id: SnowflakeId, body: AdminMenuUpdateRequest) =>
    adminApi.put<AdminMenuItem>(`/admin/admin-menus/${id}`, body),

  sort: (id: SnowflakeId, direction: AdminMenuSortDirection) =>
    adminApi.patch<AdminMenuItem>(`/admin/admin-menus/${id}/sort`, { direction }),

  remove: (id: SnowflakeId) =>
    adminApi.delete<void>(`/admin/admin-menus/${id}`),
};
