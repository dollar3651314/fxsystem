import { describe, expect, it, vi, beforeEach } from "vitest";
import { walletProvisionApi } from "./walletProvisionApi";
import { adminApi } from "../../lib/api/apiClient";

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe("walletProvisionApi (TC-WP-FE-050 ~ 051)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("TC-WP-FE-050a: list buildQuery 透传 status / userId / page / size", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 2,
      pageSize: 50,
      total: 0,
      items: [],
    });
    await walletProvisionApi.list({
      status: 0,
      userId: 80060101,
      page: 2,
      size: 50,
    });
    expect(adminApi.get).toHaveBeenCalledTimes(1);
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/wallet\/provision-dlq\?/);
    expect(url).toContain("status=0");
    expect(url).toContain("userId=80060101");
    expect(url).toContain("page=2");
    expect(url).toContain("size=50");
  });

  it("TC-WP-FE-050b: list 默认分页 page=1 size=20，可选参数不出现在 URL", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1,
      pageSize: 20,
      total: 0,
      items: [],
    });
    await walletProvisionApi.list({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=1");
    expect(url).toContain("size=20");
    expect(url).not.toContain("status=");
    expect(url).not.toContain("userId=");
  });

  it("TC-WP-FE-050c: list status=undefined / userId=undefined 不出现在 URL", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1,
      pageSize: 20,
      total: 0,
      items: [],
    });
    await walletProvisionApi.list({ status: undefined, userId: undefined });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).not.toContain("status=");
    expect(url).not.toContain("userId=");
  });

  it("TC-WP-FE-050d: list status=1 (RESOLVED) 正确传值（不是 truthy 才传）", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1,
      pageSize: 20,
      total: 0,
      items: [],
    });
    await walletProvisionApi.list({ status: 1 });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("status=1");
  });

  it("TC-WP-FE-051: retry POST body 含 reason", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await walletProvisionApi.retry("555000001", "xpub 已补齐");
    expect(adminApi.post).toHaveBeenCalledWith(
      "/admin/wallet/provision-dlq/555000001/retry",
      { reason: "xpub 已补齐" },
    );
  });
});
