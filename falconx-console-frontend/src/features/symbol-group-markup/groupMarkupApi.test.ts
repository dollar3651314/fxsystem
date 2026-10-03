import { describe, expect, it, vi, beforeEach } from "vitest";
import { groupMarkupApi } from "./groupMarkupApi";
import { adminApi } from "../../lib/api/apiClient";

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe("groupMarkupApi (TC-GM-FE-001 ~ 005)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("TC-GM-FE-001a: list 透传 groupCode/symbolLike/enabled/page/size", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
    await groupMarkupApi.list({ groupCode: "vip", symbolLike: "BTC", enabled: true, page: 2, size: 50 });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/symbols\/group-markup\?/);
    expect(url).toContain("groupCode=vip");
    expect(url).toContain("symbolLike=BTC");
    expect(url).toContain("enabled=1");
    expect(url).toContain("page=2");
    expect(url).toContain("size=50");
  });

  it("TC-GM-FE-001b: list 可选参数空值不出现在 URL", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
    await groupMarkupApi.list({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=0");
    expect(url).toContain("size=20");
    expect(url).not.toContain("groupCode=");
    expect(url).not.toContain("symbolLike=");
    expect(url).not.toContain("enabled=");
  });

  it("TC-GM-FE-001c: list enabled=0 (停用) 正确传值（不是 truthy 才传）", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
    await groupMarkupApi.list({ enabled: false });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("enabled=0");
  });

  it("TC-GM-FE-002: listGrouped 调用 /grouped 端点", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({ groups: [] });
    await groupMarkupApi.listGrouped();
    expect(adminApi.get).toHaveBeenCalledWith("/admin/symbols/group-markup/grouped");
  });

  it("TC-GM-FE-003: detail URL 含 encodeURIComponent", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await groupMarkupApi.detail("vip-group", "BTC/USDT");
    expect(adminApi.get).toHaveBeenCalledWith(
      "/admin/symbols/group-markup/vip-group/BTC%2FUSDT",
    );
  });

  it("TC-GM-FE-004a: create POST body 透传字段", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await groupMarkupApi.create({
      groupCode: "vip",
      platformSymbol: "BTCUSDT",
      bidExtra: 0.5,
      askExtra: -0.3,
      enabled: true,
      reason: "VIP 组开放 BTC 双边优惠（ticket #123）",
    });
    expect(adminApi.post).toHaveBeenCalledWith(
      "/admin/symbols/group-markup",
      expect.objectContaining({
        groupCode: "vip",
        platformSymbol: "BTCUSDT",
        bidExtra: 0.5,
        askExtra: -0.3,
        enabled: true,
        reason: "VIP 组开放 BTC 双边优惠（ticket #123）",
      }),
    );
  });

  it("TC-GM-FE-004b: update PUT URL + body 不含 group/symbol（path 已带）", async () => {
    (adminApi.put as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await groupMarkupApi.update("vip", "BTCUSDT", {
      bidExtra: 1.0,
      askExtra: 1.0,
      enabled: false,
      reason: "VIP 组暂停 BTC 加点（合规整改）",
    });
    expect(adminApi.put).toHaveBeenCalledWith(
      "/admin/symbols/group-markup/vip/BTCUSDT",
      expect.objectContaining({
        bidExtra: 1.0,
        askExtra: 1.0,
        enabled: false,
        reason: "VIP 组暂停 BTC 加点（合规整改）",
      }),
    );
  });

  it("TC-GM-FE-004c: bulkUpsert PUT items 透传", async () => {
    (adminApi.put as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    await groupMarkupApi.bulkUpsert("vip", {
      items: [
        { platformSymbol: "BTCUSDT", bidExtra: 0.1, askExtra: 0.1, enabled: true },
        { platformSymbol: "ETHUSDT", bidExtra: 0.2, askExtra: 0.2, enabled: true },
      ],
      reason: "VIP 组批量调整双边加点（季度调价）",
    });
    expect(adminApi.put).toHaveBeenCalledWith(
      "/admin/symbols/group-markup/vip/bulk",
      expect.objectContaining({
        items: expect.arrayContaining([
          expect.objectContaining({ platformSymbol: "BTCUSDT", bidExtra: 0.1 }),
          expect.objectContaining({ platformSymbol: "ETHUSDT", bidExtra: 0.2 }),
        ]),
        reason: "VIP 组批量调整双边加点（季度调价）",
      }),
    );
  });

  it("TC-GM-FE-005: delete DELETE 透传 reason body", async () => {
    (adminApi.delete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    await groupMarkupApi.delete("vip", "BTCUSDT", { reason: "VIP 组停用，废弃配置回收" });
    expect(adminApi.delete).toHaveBeenCalledWith(
      "/admin/symbols/group-markup/vip/BTCUSDT",
      { reason: "VIP 组停用，废弃配置回收" },
    );
  });
});
