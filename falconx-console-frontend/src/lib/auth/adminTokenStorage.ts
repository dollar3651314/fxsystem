/** 管理端 token 存储 (sessionStorage，关闭浏览器即销毁；按 DESIGN §10 安全约束)。
 * 禁止写入 localStorage 或 IndexedDB。 */
const ACCESS_KEY = "falconx_admin_access_token";
const REFRESH_KEY = "falconx_admin_refresh_token";
const ADMIN_USER_KEY = "falconx_admin_user";
const EXPIRES_AT_KEY = "falconx_admin_access_expires_at";

export interface PersistedAdminUser {
  adminUserId: number;
  username: string;
  realName: string | null;
  roles: string[];
  mustChangePassword: boolean;
}

export function saveAdminTokens(
  accessToken: string,
  refreshToken: string,
  expiresInSeconds: number,
  user: PersistedAdminUser,
): void {
  const expiresAt = Date.now() + expiresInSeconds * 1000;
  sessionStorage.setItem(ACCESS_KEY, accessToken);
  sessionStorage.setItem(REFRESH_KEY, refreshToken);
  sessionStorage.setItem(EXPIRES_AT_KEY, String(expiresAt));
  sessionStorage.setItem(ADMIN_USER_KEY, JSON.stringify(user));
}

export function getAdminAccessToken(): string | null {
  return sessionStorage.getItem(ACCESS_KEY);
}

export function getAdminRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_KEY);
}

export function getAdminAccessExpiresAt(): number | null {
  const value = sessionStorage.getItem(EXPIRES_AT_KEY);
  return value ? Number(value) : null;
}

export function getPersistedAdminUser(): PersistedAdminUser | null {
  const value = sessionStorage.getItem(ADMIN_USER_KEY);
  if (!value) return null;
  try {
    return JSON.parse(value) as PersistedAdminUser;
  } catch {
    return null;
  }
}

export function clearAdminTokens(): void {
  sessionStorage.removeItem(ACCESS_KEY);
  sessionStorage.removeItem(REFRESH_KEY);
  sessionStorage.removeItem(EXPIRES_AT_KEY);
  sessionStorage.removeItem(ADMIN_USER_KEY);
}
