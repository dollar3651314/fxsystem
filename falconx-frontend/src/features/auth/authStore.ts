import { create } from "zustand";
import { registerRefreshHandler } from "../../lib/authRefresh";
import { refresh as refreshTokenApi } from "./authApi";

export type UserStatus = "ACTIVE" | "FROZEN" | "BANNED";

export type AuthTokenResponse = {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresIn: number;
  refreshTokenExpiresIn: number;
  userStatus: UserStatus;
  emailVerified: boolean;
};

export type AuthSession = {
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresAt: number;
  refreshTokenExpiresAt: number;
  userStatus: UserStatus;
  emailVerified: boolean;
};

type AuthState = {
  session: AuthSession | null;
  notice: string | null;
  setSession: (session: AuthSession) => void;
  setNotice: (notice: string | null) => void;
  clearSession: (notice?: string | null) => void;
};

const storageKey = "falconx.auth.session";

function readStoredSession(): AuthSession | null {
  if (typeof window === "undefined") {
    return null;
  }

  const raw = window.localStorage.getItem(storageKey);
  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as AuthSession;
  } catch {
    window.localStorage.removeItem(storageKey);
    return null;
  }
}

export function toAuthSession(response: AuthTokenResponse, now = Date.now()): AuthSession {
  return {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken,
    accessTokenExpiresAt: now + response.accessTokenExpiresIn * 1000,
    refreshTokenExpiresAt: now + response.refreshTokenExpiresIn * 1000,
    userStatus: response.userStatus,
    emailVerified: response.emailVerified
  };
}

export const useAuthStore = create<AuthState>((set) => ({
  session: readStoredSession(),
  notice: null,
  setSession: (session) => {
    window.localStorage.setItem(storageKey, JSON.stringify(session));
    set({ session, notice: null });
  },
  setNotice: (notice) => {
    set({ notice });
  },
  clearSession: (notice = null) => {
    window.localStorage.removeItem(storageKey);
    set({ session: null, notice });
  }
}));

// 2026-05-27 P0：注册 access token 自动续期 handler。
// lib/api 与各独立 fetch API 在 401 时调 tryRefreshAccessToken() → 触发此 handler：
// 用 refreshToken（72h）换新 token bundle，写回 session，返回新 accessToken 供重试。
// refreshToken 缺失/过期/刷新失败返回 null → 调用方 notifyAuthExpired 登出。
registerRefreshHandler(async () => {
  const session = useAuthStore.getState().session;
  if (!session?.refreshToken) {
    return null;
  }
  if (session.refreshTokenExpiresAt <= Date.now()) {
    return null;
  }
  try {
    const bundle = await refreshTokenApi(session.refreshToken);
    const newSession = toAuthSession(bundle);
    useAuthStore.getState().setSession(newSession);
    return newSession.accessToken;
  } catch {
    return null;
  }
});
