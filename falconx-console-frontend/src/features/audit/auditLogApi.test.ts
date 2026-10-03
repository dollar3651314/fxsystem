import { describe, expect, it, vi, beforeEach } from "vitest";
import { auditLogApi } from "./auditLogApi";
import { adminApi } from "../../lib/api/apiClient";

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe("auditLogApi (TC-AUDIT-FE-050 ~ 052)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("TC-AUDIT-FE-050: list 透传 7 query 参数（adminUserId/permissionCode/targetType/targetId/riskLevel/from/to）", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await auditLogApi.list({
      adminUserId: "1001",
      permissionCode: "withdraw:review",
      targetType: "withdraw",
      targetId: "900000001",
      riskLevel: "HIGH_RISK",
      fromOccurredAt: "2026-05-15T00:00:00Z",
      toOccurredAt: "2026-05-16T00:00:00Z",
    });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/audit-logs\?/);
    expect(url).toContain("adminUserId=1001");
    expect(url).toContain("permissionCode=withdraw%3Areview");
    expect(url).toContain("targetType=withdraw");
    expect(url).toContain("targetId=900000001");
    expect(url).toContain("riskLevel=HIGH_RISK");
    expect(url).toContain("fromOccurredAt=");
    expect(url).toContain("toOccurredAt=");
  });

  it("TC-AUDIT-FE-051: list 默认分页 page=1 size=20，可选参数不出现", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await auditLogApi.list({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=1");
    expect(url).toContain("size=20");
    expect(url).not.toContain("adminUserId=");
    expect(url).not.toContain("permissionCode=");
    expect(url).not.toContain("riskLevel=");
  });

  it("TC-AUDIT-FE-052: detail 路径含 id", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await auditLogApi.detail("777000001");
    expect(adminApi.get).toHaveBeenCalledWith("/admin/audit-logs/777000001");
  });
});
