import { describe, expect, it, vi, beforeEach } from "vitest";
import { reconciliationApi } from "./reconciliationApi";
import { adminApi } from "../../lib/api/apiClient";

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe("reconciliationApi (TC-RECON-FE-050 ~ 052)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("TC-RECON-FE-050: listUnmatched 透传 5 query 参数（chain/token/discrepancyType/from/to）", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await reconciliationApi.listUnmatched({
      chain: "ETH",
      token: "USDT",
      discrepancyType: "WALLET_ONLY",
      fromDetectedAt: "2026-05-15T00:00:00Z",
      toDetectedAt: "2026-05-16T00:00:00Z",
    });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/reconciliation\/deposits\/unmatched\?/);
    expect(url).toContain("chain=ETH");
    expect(url).toContain("token=USDT");
    expect(url).toContain("discrepancyType=WALLET_ONLY");
    expect(url).toContain("fromDetectedAt=");
    expect(url).toContain("toDetectedAt=");
  });

  it("TC-RECON-FE-051: listUnmatched 默认分页 page=1 size=20，可选参数不出现", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await reconciliationApi.listUnmatched({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=1");
    expect(url).toContain("size=20");
    expect(url).not.toContain("chain=");
    expect(url).not.toContain("discrepancyType=");
  });

  it("TC-RECON-FE-052: markResolved POST body 含 reason + resolutionType，路径含 walletTxId", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({
      walletTxId: "880000001", resolvedAt: "2026-05-15T04:00:00Z", resolvedByAdminId: "1001",
    });
    await reconciliationApi.markResolved("880000001", {
      reason: "运营测试已手工补单",
      resolutionType: "MANUAL_CREDIT",
    });
    expect(adminApi.post).toHaveBeenCalledWith(
      "/admin/reconciliation/deposits/880000001/mark-resolved",
      { reason: "运营测试已手工补单", resolutionType: "MANUAL_CREDIT" },
    );
  });
});
