import { adminApi } from "../../lib/api/apiClient";
import type {
  AdminAuthTokenResponse,
  AdminMeMenusResponse,
  AdminMePermissionsResponse,
  AdminMeResponse,
} from "./types";

export const adminAuthApi = {
  login: (username: string, password: string) =>
    adminApi.post<AdminAuthTokenResponse>(
      "/admin/auth/login",
      { username, password },
      { skipAuthRefresh: true },
    ),

  refresh: (refreshToken: string) =>
    adminApi.post<AdminAuthTokenResponse>(
      "/admin/auth/refresh",
      { refreshToken },
      { skipAuthRefresh: true },
    ),

  logout: () =>
    adminApi.post<null>("/admin/auth/logout", undefined, { skipAuthRefresh: true }),

  changePassword: (oldPassword: string, newPassword: string) =>
    adminApi.post<null>("/admin/auth/change-password", { oldPassword, newPassword }),

  me: () => adminApi.get<AdminMeResponse>("/admin/me"),

  mePermissions: () => adminApi.get<AdminMePermissionsResponse>("/admin/me/permissions"),

  meMenus: () => adminApi.get<AdminMeMenusResponse>("/admin/me/menus"),
};
