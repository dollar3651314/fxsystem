import { requestJson } from "../../lib/api";
import { type AuthTokenResponse } from "./authStore";

type RegisterResponse = {
  userId: number;
  uid: string;
  email: string;
  status: string;
  emailVerified: boolean;
};

/**
 * STAGE-1B-USER-PROFILE：注册请求 5 PII 字段（first/middle?/last/birth/nationality）。
 * 后端 jakarta validation + 18 岁 + ISO 字典 + 姓名正则 4 重校验。
 */
export type RegisterCommand = {
  email: string;
  password: string;
  firstName: string;
  middleName?: string | null;
  lastName: string;
  /** ISO yyyy-MM-dd */
  birthDate: string;
  /** ISO 3166-1 alpha-3, e.g. CHN/USA/JPN */
  nationality: string;
};

let refreshInFlight: Promise<AuthTokenResponse> | null = null;

export function register(command: RegisterCommand) {
  return requestJson<RegisterResponse>("/api/v1/auth/register", {
    method: "POST",
    skipAuthRefresh: true,
    body: JSON.stringify({
      email: command.email,
      password: command.password,
      firstName: command.firstName,
      middleName: command.middleName ?? null,
      lastName: command.lastName,
      birthDate: command.birthDate,
      nationality: command.nationality,
    }),
  });
}

export function login(email: string, password: string) {
  return requestJson<AuthTokenResponse>("/api/v1/auth/login", {
    method: "POST",
    skipAuthRefresh: true,
    body: JSON.stringify({ email, password })
  });
}

export function refresh(refreshToken: string) {
  // skipAuthRefresh：refresh 请求本身 401 不能再触发 refresh（否则递归）
  if (!refreshInFlight) {
    refreshInFlight = requestJson<AuthTokenResponse>("/api/v1/auth/refresh", {
      method: "POST",
      skipAuthRefresh: true,
      body: JSON.stringify({ refreshToken })
    }).finally(() => {
      refreshInFlight = null;
    });
  }

  return refreshInFlight;
}

export function logout(accessToken: string) {
  return requestJson<null>("/api/v1/auth/logout", {
    method: "POST",
    skipAuthRefresh: true,
    token: accessToken
  });
}
