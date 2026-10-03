import { afterEach, describe, expect, it, vi } from "vitest";
import { AUTH_EXPIRED_EVENT } from "./authEvents";
import { FalconApiError, requestJson, unwrapApiResponse } from "./api";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("unwrapApiResponse", () => {
  it("returns data when backend code is success", () => {
    expect(
      unwrapApiResponse({
        code: "0",
        message: "success",
        data: { symbol: "BTCUSD.p" },
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "abc"
      })
    ).toEqual({ symbol: "BTCUSD.p" });
  });

  it("throws a typed error with code and traceId when backend code fails", () => {
    expect(() =>
      unwrapApiResponse({
        code: "10005",
        message: "Invalid Credentials",
        data: null,
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "trace-1"
      })
    ).toThrow(FalconApiError);
  });

  it("dispatches auth-expired when a protected API returns 401", async () => {
    const listener = vi.fn();
    window.addEventListener(AUTH_EXPIRED_EVENT, listener);
    vi.spyOn(globalThis, "fetch").mockResolvedValue({
      status: 401,
      json: async () => ({
        code: "10001",
        message: "Unauthorized",
        data: null,
        timestamp: "2026-04-30T12:00:00Z",
        traceId: "trace-401"
      })
    } as Response);

    await expect(requestJson("/api/v1/market/symbols", { token: "expired" }))
      .rejects.toMatchObject({ code: "10001", status: 401 });
    expect(listener).toHaveBeenCalledTimes(1);
    window.removeEventListener(AUTH_EXPIRED_EVENT, listener);
  });
});
