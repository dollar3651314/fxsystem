import { afterEach, describe, expect, it } from "vitest";
import { toAuthSession, useAuthStore } from "./authStore";

afterEach(() => {
  window.localStorage.clear();
  useAuthStore.setState({ session: null, notice: null });
});

describe("toAuthSession", () => {
  it("converts expiry seconds to absolute timestamps", () => {
    const session = toAuthSession(
      {
        accessToken: "access",
        refreshToken: "refresh",
        accessTokenExpiresIn: 900,
        refreshTokenExpiresIn: 259200,
        userStatus: "ACTIVE",
        emailVerified: false
      },
      1000
    );

    expect(session.accessTokenExpiresAt).toBe(901000);
    expect(session.refreshTokenExpiresAt).toBe(259201000);
  });

  it("keeps an auth notice after clearing an expired session", () => {
    useAuthStore.getState().clearSession("登录状态已过期，请重新登录。");

    expect(useAuthStore.getState().session).toBeNull();
    expect(useAuthStore.getState().notice).toBe("登录状态已过期，请重新登录。");
  });
});
