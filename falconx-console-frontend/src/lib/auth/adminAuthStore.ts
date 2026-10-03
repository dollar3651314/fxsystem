import { create } from "zustand";
import {
  clearAdminTokens,
  getAdminAccessToken,
  getPersistedAdminUser,
  saveAdminTokens,
  type PersistedAdminUser,
} from "./adminTokenStorage";

interface AdminAuthState {
  user: PersistedAdminUser | null;
  permissions: string[];
  isSuperAdmin: boolean;
  /** 从 sessionStorage 恢复 (页面刷新时调用)。 */
  hydrate: () => void;
  /** 登录成功后保存 token + user 到 sessionStorage 并刷新内存。 */
  loginSuccess: (
    accessToken: string,
    refreshToken: string,
    expiresInSeconds: number,
    user: PersistedAdminUser,
  ) => void;
  /** 登出 / 改密成功后清空。 */
  clear: () => void;
  /** /admin/me/permissions 加载完成后调用。 */
  setPermissions: (permissions: string[], isSuperAdmin: boolean) => void;
}

export const useAdminAuthStore = create<AdminAuthState>((set) => ({
  user: getPersistedAdminUser(),
  permissions: [],
  isSuperAdmin: false,
  hydrate: () => {
    set({ user: getPersistedAdminUser() });
  },
  loginSuccess: (accessToken, refreshToken, expiresInSeconds, user) => {
    saveAdminTokens(accessToken, refreshToken, expiresInSeconds, user);
    set({ user });
  },
  clear: () => {
    clearAdminTokens();
    set({ user: null, permissions: [], isSuperAdmin: false });
  },
  setPermissions: (permissions, isSuperAdmin) => {
    set({ permissions, isSuperAdmin });
  },
}));

/** 当前是否已登录。 */
export function isAuthenticated(): boolean {
  return Boolean(getAdminAccessToken() && getPersistedAdminUser());
}
