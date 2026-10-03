import { describe, expect, it } from "vitest";
import { registerRefreshHandler, tryRefreshAccessToken } from "./authRefresh";

describe("tryRefreshAccessToken", () => {
  it("single-flights concurrent refresh calls (并发 401 共享同一次刷新)", async () => {
    let calls = 0;
    let release: (value: string | null) => void = () => undefined;
    registerRefreshHandler(() => {
      calls += 1;
      return new Promise<string | null>((resolve) => {
        release = resolve;
      });
    });

    const first = tryRefreshAccessToken();
    const second = tryRefreshAccessToken();
    release("token-new");

    expect(await first).toBe("token-new");
    expect(await second).toBe("token-new");
    expect(calls).toBe(1);
  });

  it("starts a new refresh after the previous one settles", async () => {
    let calls = 0;
    registerRefreshHandler(async () => {
      calls += 1;
      return `token-${calls}`;
    });

    expect(await tryRefreshAccessToken()).toBe("token-1");
    expect(await tryRefreshAccessToken()).toBe("token-2");
    expect(calls).toBe(2);
  });

  it("releases the in-flight slot when the handler rejects", async () => {
    let calls = 0;
    registerRefreshHandler(() => {
      calls += 1;
      return calls === 1 ? Promise.reject(new Error("network")) : Promise.resolve("token-ok");
    });

    await expect(tryRefreshAccessToken()).rejects.toThrow("network");
    expect(await tryRefreshAccessToken()).toBe("token-ok");
    expect(calls).toBe(2);
  });
});
