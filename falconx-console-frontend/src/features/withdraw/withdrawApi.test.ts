import { describe, expect, it, vi, beforeEach } from "vitest";
import { withdrawApi } from "./withdrawApi";
import { adminApi } from "../../lib/api/apiClient";

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe("withdrawApi (TC-WD-FE-001 ~ 005)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("TC-WD-FE-001: list buildQuery 透传 6 个参数", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1,
      pageSize: 20,
      total: 0,
      items: [],
    });
    await withdrawApi.list({
      status: "PENDING",
      userId: "48275236470263808",
      network: "ERC20",
      minAmount: "100",
      maxAmount: "5000",
      page: 2,
      pageSize: 50,
    });
    expect(adminApi.get).toHaveBeenCalledTimes(1);
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/withdraws\?/);
    expect(url).toContain("status=PENDING");
    expect(url).toContain("userId=48275236470263808");
    expect(url).toContain("network=ERC20");
    expect(url).toContain("minAmount=100");
    expect(url).toContain("maxAmount=5000");
    expect(url).toContain("page=2");
    expect(url).toContain("pageSize=50");
  });

  it("TC-WD-FE-002: list 默认分页 page=1 pageSize=20，无可选参数不出现在 URL", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1,
      pageSize: 20,
      total: 0,
      items: [],
    });
    await withdrawApi.list({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=1");
    expect(url).toContain("pageSize=20");
    expect(url).not.toContain("status=");
    expect(url).not.toContain("userId=");
    expect(url).not.toContain("network=");
  });

  it("TC-WD-FE-003: detail 调 GET /admin/withdraws/:id", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await withdrawApi.detail("900000001");
    expect(adminApi.get).toHaveBeenCalledWith("/admin/withdraws/900000001");
  });

  it("TC-WD-FE-004: approve 带 reviewNote 时 body 含 reviewNote 字段", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await withdrawApi.approve("900000001", "合规审核已通过");
    expect(adminApi.post).toHaveBeenCalledWith(
      "/admin/withdraws/900000001/approve",
      { reviewNote: "合规审核已通过" },
    );
  });

  it("TC-WD-FE-004b: approve 无 reviewNote 时 body 是空对象（不发送 reviewNote 字段）", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await withdrawApi.approve("900000001");
    expect(adminApi.post).toHaveBeenCalledWith("/admin/withdraws/900000001/approve", {});
  });

  it("TC-WD-FE-004c: approve 空白 reviewNote 视为未填，不发送", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await withdrawApi.approve("900000001", "   ");
    expect(adminApi.post).toHaveBeenCalledWith("/admin/withdraws/900000001/approve", {});
  });

  it("TC-WD-FE-005: reject body 含 reason", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await withdrawApi.reject("900000001", "不符合 KYC 要求");
    expect(adminApi.post).toHaveBeenCalledWith(
      "/admin/withdraws/900000001/reject",
      { reason: "不符合 KYC 要求" },
    );
  });

  it("TC-WD-FE-005b: emergency-cancel body 含 reason", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await withdrawApi.emergencyCancel("900000001", "风控紧急取消");
    expect(adminApi.post).toHaveBeenCalledWith(
      "/admin/withdraws/900000001/emergency-cancel",
      { reason: "风控紧急取消" },
    );
  });
});
