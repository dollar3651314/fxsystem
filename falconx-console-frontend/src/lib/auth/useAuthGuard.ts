import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { isAuthenticated } from "./adminAuthStore";

/** 路由级鉴权守卫：仅在完全无 token 时跳转 /admin/login。 */
export function useAuthGuard(): void {
  const navigate = useNavigate();
  useEffect(() => {
    // 2026-05-27 P0：不再因 access token(30m) 过期主动登出 —— 那会抢在 apiClient
    // 401→refresh 之前把用户踢出。access 过期由 apiClient 自动用 refreshToken(8h) 续期；
    // refresh 也失败时 apiClient 已 clearAdminTokens + 跳转 /admin/login。
    // 这里只兜底「完全无 token」（从未登录 / 已被清空）的情况。
    if (!isAuthenticated()) {
      navigate("/admin/login", { replace: true });
    }
  }, [navigate]);
}
