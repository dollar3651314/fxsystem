import { afterEach, describe, expect, it } from "vitest";
import {
  clearAdminTokens,
  getAdminAccessToken,
  getAdminRefreshToken,
  getPersistedAdminUser,
  saveAdminTokens,
} from "./adminTokenStorage";

describe("adminTokenStorage (FE-CONSOLE-039 ~ 042)", () => {
  afterEach(() => {
    clearAdminTokens();
    sessionStorage.clear();
    localStorage.clear();
  });

  it("FE-CONSOLE-039: saveAdminTokens 写入 sessionStorage", () => {
    saveAdminTokens("at", "rt", 60, {
      adminUserId: 1,
      username: "alice",
      realName: "Alice",
      roles: ["FINANCE"],
      mustChangePassword: false,
    });
    expect(getAdminAccessToken()).toBe("at");
    expect(getAdminRefreshToken()).toBe("rt");
    expect(getPersistedAdminUser()?.username).toBe("alice");
  });

  it("FE-CONSOLE-040: token 不写入 localStorage", () => {
    saveAdminTokens("at", "rt", 60, {
      adminUserId: 1,
      username: "alice",
      realName: null,
      roles: [],
      mustChangePassword: false,
    });
    expect(localStorage.getItem("falconx_admin_access_token")).toBeNull();
    expect(localStorage.getItem("falconx_admin_refresh_token")).toBeNull();
  });

  it("FE-CONSOLE-041: clearAdminTokens 清空所有 token", () => {
    saveAdminTokens("at", "rt", 60, {
      adminUserId: 1,
      username: "alice",
      realName: null,
      roles: [],
      mustChangePassword: false,
    });
    clearAdminTokens();
    expect(getAdminAccessToken()).toBeNull();
    expect(getAdminRefreshToken()).toBeNull();
    expect(getPersistedAdminUser()).toBeNull();
  });

  it("getPersistedAdminUser 损坏 JSON 返回 null", () => {
    sessionStorage.setItem("falconx_admin_user", "{broken json");
    expect(getPersistedAdminUser()).toBeNull();
  });
});
