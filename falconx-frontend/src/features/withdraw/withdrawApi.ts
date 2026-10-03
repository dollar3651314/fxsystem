import { apiBaseUrl } from "../../lib/config";
import { type ApiResponse, FalconApiError, requestJson, withAccessTokenRetry } from "../../lib/api";
import type {
  AddWhitelistRequest,
  SubmitWithdrawRequest,
  WithdrawListResponse,
  WithdrawOrder,
  WithdrawWhitelistItem,
} from "./types";

/** 雪花 ID 字段集：在 JSON.parse 之前把 number 替换成 string 防 2^53 截断。 */
const ID_FIELDS_RE = /("(?:withdrawId|userId|whitelistId|id)":)\s*(\d{15,})/g;

/**
 * 出金提交特殊处理：必须发 X-Idempotency-Key 头。requestJson 不暴露任意 header，
 * 因此在本模块走一次 fetch + 复用 ApiResponse 解码 / 雪花 ID 字符串化。
 */
export async function submitWithdraw(
  token: string,
  request: SubmitWithdrawRequest,
  idempotencyKey: string,
): Promise<WithdrawOrder> {
  return withAccessTokenRetry(token, async (tk) => {
    const resp = await fetch(`${apiBaseUrl}/api/v1/me/withdraw`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${tk}`,
        "X-Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify(request),
    });
    const raw = await resp.text();
    const wrapped = raw.replace(ID_FIELDS_RE, '$1"$2"');
    const payload = JSON.parse(wrapped) as ApiResponse<WithdrawOrder>;
    const unauthorized = resp.status === 401 || payload.code === "10001";
    if (!unauthorized && (payload.code !== "0" || payload.data == null)) {
      throw new FalconApiError(payload.code, payload.message, payload.traceId, resp.status, payload.data);
    }
    return { unauthorized, value: payload.data as WithdrawOrder };
  });
}

export async function listWithdraws(
  token: string,
  page = 1,
  pageSize = 20,
  status?: string,
): Promise<WithdrawListResponse> {
  const params = new URLSearchParams();
  params.set("page", String(page));
  params.set("pageSize", String(pageSize));
  if (status) params.set("status", status);
  return withAccessTokenRetry(token, async (tk) => {
    const resp = await fetch(`${apiBaseUrl}/api/v1/me/withdraw?${params.toString()}`, {
      headers: { Authorization: `Bearer ${tk}` },
    });
    const raw = await resp.text();
    const wrapped = raw.replace(ID_FIELDS_RE, '$1"$2"');
    const payload = JSON.parse(wrapped) as ApiResponse<WithdrawListResponse>;
    const unauthorized = resp.status === 401 || payload.code === "10001";
    if (!unauthorized && (payload.code !== "0" || payload.data == null)) {
      throw new FalconApiError(payload.code, payload.message, payload.traceId, resp.status, payload.data);
    }
    return { unauthorized, value: payload.data as WithdrawListResponse };
  });
}

export function cancelWithdraw(token: string, withdrawId: string): Promise<WithdrawOrder> {
  return requestJson<WithdrawOrder>(`/api/v1/me/withdraw/${withdrawId}/cancel`, {
    method: "POST",
    token,
    body: "{}",
  });
}

export async function listWhitelists(token: string): Promise<WithdrawWhitelistItem[]> {
  return withAccessTokenRetry(token, async (tk) => {
    const resp = await fetch(`${apiBaseUrl}/api/v1/me/withdraw/whitelist`, {
      headers: { Authorization: `Bearer ${tk}` },
    });
    const raw = await resp.text();
    const wrapped = raw.replace(ID_FIELDS_RE, '$1"$2"');
    const payload = JSON.parse(wrapped) as ApiResponse<WithdrawWhitelistItem[]>;
    const unauthorized = resp.status === 401 || payload.code === "10001";
    if (!unauthorized && (payload.code !== "0" || payload.data == null)) {
      throw new FalconApiError(payload.code, payload.message, payload.traceId, resp.status, payload.data);
    }
    return { unauthorized, value: payload.data as WithdrawWhitelistItem[] };
  });
}

export async function addWhitelist(token: string, request: AddWhitelistRequest): Promise<WithdrawWhitelistItem> {
  return withAccessTokenRetry(token, async (tk) => {
    const resp = await fetch(`${apiBaseUrl}/api/v1/me/withdraw/whitelist`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${tk}` },
      body: JSON.stringify(request),
    });
    const raw = await resp.text();
    const wrapped = raw.replace(ID_FIELDS_RE, '$1"$2"');
    const payload = JSON.parse(wrapped) as ApiResponse<WithdrawWhitelistItem>;
    const unauthorized = resp.status === 401 || payload.code === "10001";
    if (!unauthorized && (payload.code !== "0" || payload.data == null)) {
      throw new FalconApiError(payload.code, payload.message, payload.traceId, resp.status, payload.data);
    }
    return { unauthorized, value: payload.data as WithdrawWhitelistItem };
  });
}

export function deleteWhitelist(token: string, id: string): Promise<WithdrawWhitelistItem> {
  return requestJson<WithdrawWhitelistItem>(`/api/v1/me/withdraw/whitelist/${id}`, {
    method: "DELETE",
    token,
  });
}

/** 客户端生成幂等键：cli-<ms>-<rand>（与挂单 newClientOrderId 同口径）。 */
export function newIdempotencyKey(): string {
  return `wd-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
