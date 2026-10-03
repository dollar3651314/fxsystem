/**
 * 2026-05-27 P0：access token 自动续期解耦层。
 *
 * <p>问题：access token TTL 15m，session 里有 refreshToken（72h）+ authApi.refresh()，
 * 但 refresh() 是死代码从未被调用，所有 API 401 直接 notifyAuthExpired 登出 →
 * 登录 15 分钟必失效，操作触发的请求反而加速暴露 401 登出。
 *
 * <p>本模块提供 lib 层的 refresh 注册点：auth 模块在启动时注册具体实现
 * （用 authStore.refreshToken 调 authApi.refresh + setSession），lib/api 与各
 * 独立 fetch API 通过 tryRefreshAccessToken() 触发续期，避免 lib → feature 的循环依赖。
 */

/** 返回续期后的新 accessToken；refreshToken 缺失/过期/刷新失败返回 null。 */
type RefreshHandler = () => Promise<string | null>;

let refreshHandler: RefreshHandler | null = null;
// 2026-06-04 单飞：并发 401（页面加载时多个请求同时撞过期 token）必须共享同一次 refresh。
// 否则各自调刷新接口 → 多次 setSession 轮换 accessToken → 依赖 token 的消费方（WS 连接等）
// 被反复重建；且 refresh token 若按一次性轮换语义，并发刷新会互相作废致误登出。
let inFlightRefresh: Promise<string | null> | null = null;

export function registerRefreshHandler(handler: RefreshHandler): void {
  refreshHandler = handler;
}

export function tryRefreshAccessToken(): Promise<string | null> {
  if (!refreshHandler) {
    return Promise.resolve(null);
  }
  if (!inFlightRefresh) {
    inFlightRefresh = refreshHandler().finally(() => {
      inFlightRefresh = null;
    });
  }
  return inFlightRefresh;
}
