import { apiBaseUrl } from "../../lib/config";
import { type ApiResponse, FalconApiError, requestJson, withAccessTokenRetry } from "../../lib/api";
import type { KycSubmissionResponse, SubmitKycCommand } from "./types";

/**
 * GET /api/v1/me/kyc。
 *
 * <p>未提交 KYC 时后端返回 `{code:"0", data:null}`，因此不能直接复用通用
 * {@link requestJson}（它将 `data=null` 视为业务错误）。这里单独解析，
 * 仅在 code=0 时返回 data（可能是 null），其它仍按 FalconApiError 抛出。
 */
export async function getLatestKyc(accessToken: string): Promise<KycSubmissionResponse | null> {
  return withAccessTokenRetry<KycSubmissionResponse | null>(accessToken, async (tk) => {
    const response = await fetch(`${apiBaseUrl}/api/v1/me/kyc`, {
      method: "GET",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${tk}`,
      },
    });
    const payload = (await response.json()) as ApiResponse<KycSubmissionResponse>;
    const unauthorized = response.status === 401 || payload.code === "10001";
    if (!unauthorized && payload.code !== "0") {
      throw new FalconApiError(payload.code, payload.message, payload.traceId, response.status, payload.data);
    }
    // code=0 时 data 可能为 null（未提交 KYC），属正常返回
    return { unauthorized, value: unauthorized ? null : payload.data };
  });
}

export function submitKyc(accessToken: string, command: SubmitKycCommand) {
  return requestJson<KycSubmissionResponse>("/api/v1/me/kyc", {
    method: "POST",
    token: accessToken,
    body: JSON.stringify(command),
  });
}
