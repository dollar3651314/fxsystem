import { apiBaseUrl } from "./config";
import { notifyAuthExpired } from "./authEvents";
import { tryRefreshAccessToken } from "./authRefresh";

export type ApiResponse<T> = {
  code: string;
  message: string;
  data: T | null;
  timestamp: string;
  traceId: string;
};

export class FalconApiError extends Error {
  readonly code: string;
  readonly traceId: string;
  readonly status?: number;
  readonly data?: unknown;

  constructor(code: string, message: string, traceId: string, status?: number, data?: unknown) {
    super(message);
    this.name = "FalconApiError";
    this.code = code;
    this.traceId = traceId;
    this.status = status;
    this.data = data;
  }
}

export function unwrapApiResponse<T>(
  response: ApiResponse<T>,
  status?: number
): T {
  if (response.code === "0" && response.data !== null) {
    return response.data;
  }

  // 保留 response.data 让调用方拿到业务级 reject 字段（如 trading PlaceMarketOrderResponse.rejectionReason）
  throw new FalconApiError(
    response.code,
    response.message,
    response.traceId,
    status,
    response.data
  );
}

/**
 * 默认请求超时 15s —— 后端依赖（ClickHouse / market-service）短暂故障时，
 * 不要让前端 spinner 转死，转抛 timeout 错让 useQuery retry 兜底。
 * 调用方传 signal 时尊重外部 signal，不再叠加超时。
 */
const DEFAULT_REQUEST_TIMEOUT_MS = 15_000;

export async function requestJson<T>(
  path: string,
  options: RequestInit & { token?: string; skipAuthRefresh?: boolean } = {}
): Promise<T> {
  const doFetch = async (overrideToken?: string) => {
    const headers = new Headers(options.headers);
    headers.set("Content-Type", "application/json");
    const token = overrideToken ?? options.token;
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
    const signal = options.signal ?? AbortSignal.timeout(DEFAULT_REQUEST_TIMEOUT_MS);
    const response = await fetch(`${apiBaseUrl}${path}`, { ...options, headers, signal });
    const payload = (await response.json()) as ApiResponse<T>;
    return { response, payload };
  };

  let { response, payload } = await doFetch();

  // 2026-05-27 P0：401 时先用 refreshToken 续期并重试一次，refresh 也失败才登出。
  // skipAuthRefresh 用于 login/register/refresh/logout 本身，避免递归。
  if ((response.status === 401 || payload.code === "10001") && !options.skipAuthRefresh) {
    const newAccessToken = await tryRefreshAccessToken();
    if (newAccessToken) {
      ({ response, payload } = await doFetch(newAccessToken));
    }
    if (response.status === 401 || payload.code === "10001") {
      notifyAuthExpired();
    }
  } else if (response.status === 401 || payload.code === "10001") {
    notifyAuthExpired();
  }

  return unwrapApiResponse(payload, response.status);
}

/**
 * 2026-05-27 P0：给独立 fetch API（需特殊解析，不走 requestJson）用的 401 自动续期包装。
 *
 * <p>attempt(token) 执行一次完整 fetch+解析，返回 {@code unauthorized}（HTTP 401 或
 * body code=10001）与业务 {@code value}。unauthorized 时用 refreshToken 续期并重试一次，
 * 仍未授权才 notifyAuthExpired 登出并抛错。与 requestJson 的续期语义一致。
 */
export async function withAccessTokenRetry<T>(
  token: string,
  attempt: (token: string) => Promise<{ unauthorized: boolean; value: T }>
): Promise<T> {
  let result = await attempt(token);
  if (result.unauthorized) {
    const newAccessToken = await tryRefreshAccessToken();
    if (newAccessToken) {
      result = await attempt(newAccessToken);
    }
    if (result.unauthorized) {
      notifyAuthExpired();
      throw new FalconApiError("10001", "登录已过期，请重新登录", "", 401);
    }
  }
  return result.value;
}
