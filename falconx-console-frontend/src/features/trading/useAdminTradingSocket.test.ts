import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  useAdminTradingSocket,
  type AdminExposureUpdate,
  type AdminPositionPnlUpdate,
} from "./useAdminTradingSocket";

/**
 * STAGE-14E2 Task5：admin WS 硬切双币 + exposure quoteCurrency 后，hook 必须解析后端新字段，
 * 且旧单币 unrealizedPnl 不再出现在 admin.position.update 解析结果里（与客户端 E1 同口径）。
 *
 * 用最小 FakeWebSocket 替换全局 WebSocket，捕获 hook 注册的 listener 手工灌帧，断言 payload。
 */

type Listener = (event: { data?: unknown; code?: number; type?: string }) => void;

class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  url: string;
  listeners: Record<string, Listener[]> = {};
  sent: string[] = [];
  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }
  addEventListener(type: string, cb: Listener) {
    (this.listeners[type] ??= []).push(cb);
  }
  removeEventListener() {
    /* noop */
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.emit("close", { code: 1000 });
  }
  emit(type: string, event: { data?: unknown; code?: number } = {}) {
    (this.listeners[type] ?? []).forEach((cb) => cb({ type, ...event }));
  }
}

describe("useAdminTradingSocket STAGE-14E2 双币/quoteCurrency 帧解析", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function mountAndOpen(handlers: Parameters<typeof useAdminTradingSocket>[1]) {
    renderHook(() => useAdminTradingSocket("test-token", handlers));
    const ws = FakeWebSocket.instances[0];
    expect(ws).toBeTruthy();
    act(() => ws.emit("open"));
    return ws;
  }

  function frame(ws: FakeWebSocket, payload: Record<string, unknown>) {
    act(() => ws.emit("message", { data: JSON.stringify(payload) }));
  }

  it("admin.position.update 解析双币新字段，无旧 unrealizedPnl 残留", () => {
    let captured: AdminPositionPnlUpdate | undefined;
    const ws = mountAndOpen({ onPositionPnlUpdate: (u) => { captured = u; } });

    frame(ws, {
      type: "admin.position.update",
      data: {
        symbol: "EURUSD",
        quoteTs: "2026-06-01T10:00:00Z",
        items: [
          {
            positionId: "12345678901234567",
            userId: "98765432109876543",
            side: "BUY",
            markPrice: "1.0850",
            quoteCurrency: "USD",
            fxRate: "1.0",
            unrealizedPnlInQuote: "12.50",
            unrealizedPnlInAccount: "12.50",
            // 后端已删该字段；即便残留也不应被消费
            unrealizedPnl: "999",
          },
        ],
      },
    });

    expect(captured).toBeTruthy();
    const item = captured!.items[0];
    expect(item.positionId).toBe("12345678901234567");
    expect(item.quoteCurrency).toBe("USD");
    expect(item.fxRate).toBe("1.0");
    expect(item.unrealizedPnlInQuote).toBe("12.50");
    expect(item.unrealizedPnlInAccount).toBe("12.50");
    // 旧单币字段不出现在解析结果里
    expect((item as unknown as Record<string, unknown>).unrealizedPnl).toBeUndefined();
  });

  it("admin.position.update 缺新字段时降级 null（不抛）", () => {
    let captured: AdminPositionPnlUpdate | undefined;
    const ws = mountAndOpen({ onPositionPnlUpdate: (u) => { captured = u; } });

    frame(ws, {
      type: "admin.position.update",
      data: { symbol: "BTCUSD", quoteTs: null, items: [{ positionId: "1", userId: "2" }] },
    });

    const item = captured!.items[0];
    expect(item.quoteCurrency).toBeNull();
    expect(item.fxRate).toBeNull();
    expect(item.unrealizedPnlInQuote).toBeNull();
    expect(item.unrealizedPnlInAccount).toBeNull();
  });

  it("admin.exposure.update 解析 quoteCurrency", () => {
    let captured: AdminExposureUpdate | undefined;
    const ws = mountAndOpen({ onExposureUpdate: (u) => { captured = u; } });

    frame(ws, {
      type: "admin.exposure.update",
      data: {
        symbol: "USDJPY",
        quoteCurrency: "JPY",
        totalLongQty: "10",
        totalShortQty: "3",
        netExposure: "7",
        netExposureUsd: "50000",
        quoteTs: "2026-06-01T10:00:00Z",
      },
    });

    expect(captured!.symbol).toBe("USDJPY");
    expect(captured!.quoteCurrency).toBe("JPY");
    expect(captured!.netExposureUsd).toBe("50000");
  });

  it("admin.exposure.update 缺 quoteCurrency 降级 null", () => {
    let captured: AdminExposureUpdate | undefined;
    const ws = mountAndOpen({ onExposureUpdate: (u) => { captured = u; } });

    frame(ws, {
      type: "admin.exposure.update",
      data: { symbol: "BTCUSD", netExposureUsd: "1", quoteTs: null },
    });

    expect(captured!.quoteCurrency).toBeNull();
  });
});
