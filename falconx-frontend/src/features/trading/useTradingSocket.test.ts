import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  useTradingSocket,
  type AccountStateUpdate,
  type PositionPnlUpdate,
} from "./useTradingSocket";

/**
 * STAGE-14E1 Task4：硬切双币 + marginLevel 后，hook 必须把后端新字段解析出来，
 * 且旧单币 unrealizedPnl 不再出现在解析结果里。
 *
 * 用一个最小 FakeWebSocket 替换全局 WebSocket，捕获 hook 注册的 listener，
 * 然后手工灌 message 帧，断言 handler 收到的 payload。
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

describe("useTradingSocket STAGE-14E1 双币/marginLevel 帧解析", () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal("WebSocket", FakeWebSocket as unknown as typeof WebSocket);
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  async function mountAndOpen(handlers: Parameters<typeof useTradingSocket>[1]) {
    const view = renderHook(() => useTradingSocket("test-token", handlers));
    // hook 用 setTimeout(connect, 0) 延迟建连
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    const ws = FakeWebSocket.instances[0];
    expect(ws).toBeTruthy();
    act(() => ws.emit("open"));
    return { view, ws };
  }

  function frame(ws: FakeWebSocket, payload: Record<string, unknown>) {
    act(() => ws.emit("message", { data: JSON.stringify(payload) }));
  }

  it("position.pnl 解析双币新字段，无旧 unrealizedPnl 残留", async () => {
    let captured: PositionPnlUpdate | undefined;
    const { ws } = await mountAndOpen({
      onPositionPnl: (u) => {
        captured = u;
      },
    });

    frame(ws, {
      type: "position.pnl",
      data: {
        positionId: "12345678901234567",
        symbol: "EURUSD",
        side: "BUY",
        markPrice: "1.0850",
        quoteCurrency: "USD",
        fxRate: "1.0",
        unrealizedPnlInQuote: "12.50",
        unrealizedPnlInAccount: "12.50",
        isolatedMargin: "100.00",
        liquidationDistance: "0.05",
        quoteTs: "2026-06-01T00:00:00Z",
      },
    });

    expect(captured).toBeDefined();
    expect(captured!.quoteCurrency).toBe("USD");
    expect(captured!.fxRate).toBe("1.0");
    expect(captured!.unrealizedPnlInQuote).toBe("12.50");
    expect(captured!.unrealizedPnlInAccount).toBe("12.50");
    expect(captured!.isolatedMargin).toBe("100.00");
    expect(captured!.markPrice).toBe("1.0850");
    // 硬 break 守卫：旧字段不应存在
    expect((captured as unknown as Record<string, unknown>).unrealizedPnl).toBeUndefined();
  });

  it("position.pnl 缺字段时降级为 null（不抛）", async () => {
    let captured: PositionPnlUpdate | undefined;
    const { ws } = await mountAndOpen({
      onPositionPnl: (u) => {
        captured = u;
      },
    });
    frame(ws, {
      type: "position.pnl",
      data: { positionId: "1", symbol: "EURUSD", side: "SELL", markPrice: "1.1" },
    });
    expect(captured!.unrealizedPnlInAccount).toBeNull();
    expect(captured!.isolatedMargin).toBeNull();
    expect(captured!.quoteCurrency).toBeNull();
  });

  it("account.update 解析 equity/marginLevel/marginLevelStatus/accountMarginMode", async () => {
    let captured: AccountStateUpdate | undefined;
    const { ws } = await mountAndOpen({
      onAccountUpdate: (e) => {
        captured = e;
      },
    });

    frame(ws, {
      type: "account.update",
      data: {
        currency: "USDT",
        balance: "1000",
        available: "800",
        equity: "1012.50",
        marginLevel: "5.25",
        marginLevelStatus: "HEALTHY",
        marginMode: "CROSS",
      },
    });

    expect(captured).toBeDefined();
    expect(captured!.equity).toBe("1012.50");
    expect(captured!.marginLevel).toBe("5.25");
    expect(captured!.marginLevelStatus).toBe("HEALTHY");
    // accountMarginMode 取后端账户级 marginMode
    expect(captured!.accountMarginMode).toBe("CROSS");
  });

  it("account.snapshot 走同一分支，解析账户级实时态", async () => {
    let captured: AccountStateUpdate | undefined;
    const { ws } = await mountAndOpen({
      onAccountUpdate: (e) => {
        captured = e;
      },
    });
    frame(ws, {
      type: "account.snapshot",
      data: { marginLevel: "1.05", marginLevelStatus: "STOP_OUT", marginMode: "ISOLATED" },
    });
    expect(captured!.marginLevel).toBe("1.05");
    expect(captured!.marginLevelStatus).toBe("STOP_OUT");
    expect(captured!.accountMarginMode).toBe("ISOLATED");
  });

  it("subscribe 帧在 open 后发出（连接基础健全性）", async () => {
    const { ws } = await mountAndOpen({});
    // open 回调同步 send，无需 waitFor（fake timers 下 waitFor 会卡死）
    expect(ws.sent.length).toBeGreaterThan(0);
    const sub = JSON.parse(ws.sent[0]) as { type: string; channels: string[] };
    expect(sub.type).toBe("subscribe");
    expect(sub.channels).toContain("account");
  });
});
