import { describe, expect, it, vi, beforeEach } from "vitest";
import { notificationApi } from "./notificationApi";
import { adminApi } from "../../lib/api/apiClient";

vi.mock("../../lib/api/apiClient", () => ({
  adminApi: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe("notificationApi (TC-NOTIF-FE-050 ~ 052)", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ---------- 模板 ----------

  it("TC-NOTIF-FE-050a: listTemplates 透传 enabled / level / page / size", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 2, pageSize: 50, total: 0, items: [],
    });
    await notificationApi.listTemplates({ enabled: 1, level: 2, page: 2, size: 50 });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/notification-templates\?/);
    expect(url).toContain("enabled=1");
    expect(url).toContain("level=2");
    expect(url).toContain("page=2");
    expect(url).toContain("size=50");
  });

  it("TC-NOTIF-FE-050b: listTemplates 默认分页 page=1 size=20，可选参数不出现", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await notificationApi.listTemplates({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=1");
    expect(url).toContain("size=20");
    expect(url).not.toContain("enabled=");
    expect(url).not.toContain("level=");
  });

  it("TC-NOTIF-FE-050c: createTemplate POST body 透传整个 request", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    const req = {
      code: "CUSTOM_TEST_001",
      titleTemplate: "${title}",
      bodyTemplate: "${body}",
      level: "INFO" as const,
      channels: ["IN_APP" as const],
      description: "测试模板",
      enabled: true,
    };
    await notificationApi.createTemplate(req);
    expect(adminApi.post).toHaveBeenCalledWith("/admin/notification-templates", req);
  });

  it("TC-NOTIF-FE-050d: updateTemplate PUT 走 /admin/notification-templates/:code", async () => {
    (adminApi.put as ReturnType<typeof vi.fn>).mockResolvedValue({});
    const req = {
      code: "CUSTOM_TEST_001",
      titleTemplate: "${title}",
      bodyTemplate: "${body}",
      level: "INFO" as const,
      channels: ["IN_APP" as const],
      enabled: true,
    };
    await notificationApi.updateTemplate("CUSTOM_TEST_001", req);
    expect(adminApi.put).toHaveBeenCalledWith("/admin/notification-templates/CUSTOM_TEST_001", req);
  });

  it("TC-NOTIF-FE-050e: deleteTemplate DELETE 路径含 code", async () => {
    (adminApi.delete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
    await notificationApi.deleteTemplate("CUSTOM_TEST_001");
    expect(adminApi.delete).toHaveBeenCalledWith("/admin/notification-templates/CUSTOM_TEST_001");
  });

  // ---------- 通知 ----------

  it("TC-NOTIF-FE-051a: list 透传 7 query 参数（userId/type/templateCode/level/status/from/to）", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await notificationApi.list({
      userId: "2000001",
      type: "PRICE_ALERT_TRIGGERED",
      templateCode: "PRICE_ALERT_TRIGGERED",
      level: 2,
      status: 0,
      fromCreatedAt: "2026-05-15T00:00:00Z",
      toCreatedAt: "2026-05-16T00:00:00Z",
    });
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toMatch(/^\/admin\/notifications\?/);
    expect(url).toContain("userId=2000001");
    expect(url).toContain("type=PRICE_ALERT_TRIGGERED");
    expect(url).toContain("templateCode=PRICE_ALERT_TRIGGERED");
    expect(url).toContain("level=2");
    expect(url).toContain("status=0");
    expect(url).toContain("fromCreatedAt=");
    expect(url).toContain("toCreatedAt=");
  });

  it("TC-NOTIF-FE-051b: list 默认分页 page=1 size=20", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({
      page: 1, pageSize: 20, total: 0, items: [],
    });
    await notificationApi.list({});
    const url = (adminApi.get as ReturnType<typeof vi.fn>).mock.calls[0][0] as string;
    expect(url).toContain("page=1");
    expect(url).toContain("size=20");
    expect(url).not.toContain("userId=");
  });

  it("TC-NOTIF-FE-051c: detail 路径含 id", async () => {
    (adminApi.get as ReturnType<typeof vi.fn>).mockResolvedValue({});
    await notificationApi.detail("888000001");
    expect(adminApi.get).toHaveBeenCalledWith("/admin/notifications/888000001");
  });

  // ---------- send ----------

  it("TC-NOTIF-FE-052: send POST body 含 userId / templateCode / params / reason", async () => {
    (adminApi.post as ReturnType<typeof vi.fn>).mockResolvedValue({});
    const req = {
      userId: "2000001",
      templateCode: "CUSTOM_PROMO_001",
      params: { title: "新功能", body: "FalconX 现已支持 SOL/USD" },
      reason: "运营测试通知发送链路",
    };
    await notificationApi.send(req);
    expect(adminApi.post).toHaveBeenCalledWith("/admin/notifications/send", req);
  });
});
