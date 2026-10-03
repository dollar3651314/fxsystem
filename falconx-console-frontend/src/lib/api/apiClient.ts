import { ConsoleEnv } from "../config/env";
import {
  clearAdminTokens,
  getAdminAccessToken,
  getAdminRefreshToken,
  saveAdminTokens,
  getPersistedAdminUser,
} from "../auth/adminTokenStorage";

/** 后端统一响应结构 (与 falconx-common ApiResponse 对齐)。 */
export interface ApiResponse<T> {
  code: string;
  message: string;
  data: T | null;
  timestamp: string;
  traceId: string | null;
}

/** API 业务错误。 */
export class ApiError extends Error {
  readonly code: string;
  readonly httpStatus: number;

  constructor(code: string, httpStatus: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

/** 单飞 refresh promise，并发 401 只触发一次刷新 (R6 FE-CONSOLE-015)。 */
let refreshPromise: Promise<boolean> | null = null;

/** 通过 refresh token 换新 access (一次性轮换；失败清空 token 并跳转登录)。 */
async function tryRefresh(): Promise<boolean> {
  if (refreshPromise) return refreshPromise;
  refreshPromise = (async () => {
    try {
      const refreshToken = getAdminRefreshToken();
      if (!refreshToken) return false;
      const response = await fetch(`${ConsoleEnv.apiBaseUrl}/admin/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });
      if (!response.ok) return false;
      const body = (await response.json()) as ApiResponse<{
        adminUserId: number;
        username: string;
        realName: string | null;
        roles: string[];
        mustChangePassword: boolean;
        accessToken: string;
        refreshToken: string;
        accessTokenExpiresIn: number;
      }>;
      if (body.code !== "0" || !body.data) return false;
      const persisted = getPersistedAdminUser();
      saveAdminTokens(
        body.data.accessToken,
        body.data.refreshToken,
        body.data.accessTokenExpiresIn,
        persisted ?? {
          adminUserId: body.data.adminUserId,
          username: body.data.username,
          realName: body.data.realName,
          roles: body.data.roles,
          mustChangePassword: body.data.mustChangePassword,
        },
      );
      return true;
    } catch {
      return false;
    } finally {
      refreshPromise = null;
    }
  })();
  return refreshPromise;
}

interface RequestOptions extends Omit<RequestInit, "body" | "headers"> {
  body?: unknown;
  /** 显式跳过 401 自动刷新 (登录 / 登出 / refresh 接口本身)。 */
  skipAuthRefresh?: boolean;
}

async function executeRequest<T>(path: string, options: RequestOptions): Promise<T> {
  const { body, skipAuthRefresh, ...rest } = options;
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  const accessToken = getAdminAccessToken();
  if (accessToken) headers["Authorization"] = `Bearer ${accessToken}`;
  // 浏览器禁止主动发送 X-Trace-Id (按 DESIGN §10 安全约束)，gateway 自动注入
  const response = await fetch(`${ConsoleEnv.apiBaseUrl}${path}`, {
    ...rest,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  // 401：尝试 refresh 后重试一次
  if (response.status === 401 && !skipAuthRefresh && accessToken) {
    const refreshed = await tryRefresh();
    if (refreshed) {
      return executeRequest<T>(path, { ...options, skipAuthRefresh: true });
    }
    clearAdminTokens();
    window.location.href = "/admin/login";
    throw new ApiError("90003", 401, "Unauthorized");
  }
  const apiBody = (await response.json()) as ApiResponse<T>;
  if (apiBody.code !== "0") {
    throw new ApiError(apiBody.code, response.status, apiBody.message);
  }
  return apiBody.data as T;
}

export const adminApi = {
  get: <T>(path: string) => executeRequest<T>(path, { method: "GET" }),
  post: <T>(path: string, body?: unknown, opts?: { skipAuthRefresh?: boolean }) =>
    executeRequest<T>(path, { method: "POST", body, skipAuthRefresh: opts?.skipAuthRefresh }),
  put: <T>(path: string, body?: unknown) =>
    executeRequest<T>(path, { method: "PUT", body }),
  patch: <T>(path: string, body?: unknown) =>
    executeRequest<T>(path, { method: "PATCH", body }),
  delete: <T>(path: string, body?: unknown) =>
    executeRequest<T>(path, { method: "DELETE", body }),
};
