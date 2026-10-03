import { act, render } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { chunkPriceSubscriptionSymbols } from "./useMarketSocket";
import { useMarketSocket } from "./useMarketSocket";
import { useMarketStore } from "./marketStore";

describe("useMarketSocket", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeWebSocket.instances = [];
    vi.stubGlobal("WebSocket", FakeWebSocket);
    useMarketStore.getState().reset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it("chunks large price subscriptions to avoid oversized websocket frames", () => {
    const symbols = Array.from({ length: 205 }, (_, index) => `SYM${index}`);

    expect(chunkPriceSubscriptionSymbols(symbols).map((chunk) => chunk.length)).toEqual([
      100,
      100,
      5
    ]);
  });

  it("does not create a socket when the effect is cleaned up before the deferred connect", () => {
    const { unmount } = render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));

    unmount();
    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(FakeWebSocket.instances).toHaveLength(0);
  });

  it("subscribes price ticks for the visible list and kline for the selected symbol", () => {
    render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD", "XAGUSD"],
      selectedSymbol: "XAGUSD"
    }));

    act(() => {
      vi.runOnlyPendingTimers();
    });
    act(() => {
      FakeWebSocket.instances[0].open();
    });

    const sentMessages = FakeWebSocket.instances[0].sent.map((message) => JSON.parse(message));
    expect(sentMessages).toEqual([
      expect.objectContaining({
        type: "subscribe",
        channels: ["price.tick"],
        symbols: ["EURUSD", "XAGUSD"]
      }),
      expect.objectContaining({
        type: "subscribe",
        channels: ["kline.1m"],
        symbols: ["XAGUSD"]
      })
    ]);
  });

  it("updates price subscriptions incrementally when visible rows change", () => {
    const { rerender } = render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD", "XAGUSD"],
      selectedSymbol: "XAGUSD"
    }));

    act(() => {
      vi.runOnlyPendingTimers();
    });
    act(() => {
      FakeWebSocket.instances[0].open();
    });

    rerender(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["XAGUSD", "BTCUSD"],
      selectedSymbol: "XAGUSD"
    }));

    const sentMessages = FakeWebSocket.instances[0].sent.map((message) => JSON.parse(message));
    expect(sentMessages.slice(2)).toEqual([
      expect.objectContaining({
        type: "unsubscribe",
        channels: ["price.tick"],
        symbols: ["EURUSD"]
      }),
      expect.objectContaining({
        type: "subscribe",
        channels: ["price.tick"],
        symbols: ["BTCUSD"]
      })
    ]);
  });

  it("stamps incoming price ticks with frontend receive time", () => {
    vi.setSystemTime(new Date("2026-05-01T08:00:02Z"));
    const { unmount } = render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));

    act(() => {
      // 只推进 50ms 延迟连接（runOnlyPendingTimers 会把 30s 心跳 interval 也跑掉，
      // 假时钟先跳到 +30s 才注入消息，receivedAt 断言失真）
      vi.advanceTimersByTime(50);
    });
    act(() => {
      FakeWebSocket.instances[0].open();
      FakeWebSocket.instances[0].message({
        type: "price.tick",
        symbol: "EURUSD",
        bid: "1.1700",
        ask: "1.1701",
        mid: "1.17005",
        mark: "1.17005",
        ts: "2026-05-01T08:00:00Z",
        source: "TM_QUOTE",
        stale: false,
        quoteStatus: "FRESH"
      });
    });

    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(useMarketStore.getState().quotes.EURUSD?.receivedAt).toBe(
      "2026-05-01T08:00:02.050Z"
    );
    unmount();
  });

  it("sends app-level heartbeat pings on an open socket", () => {
    render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    act(() => {
      FakeWebSocket.instances[0].open();
    });

    act(() => {
      // 两个心跳周期；期间注入一条消息保持 lastMessageAt 新鲜，确保看门狗不触发
      vi.advanceTimersByTime(30_000);
      FakeWebSocket.instances[0].message({ type: "pong", ts: "2026-05-01T08:00:32Z" });
      vi.advanceTimersByTime(30_000);
    });

    const pings = FakeWebSocket.instances[0].sent
      .map((message) => JSON.parse(message))
      .filter((message) => message.type === "ping");
    expect(pings.length).toBe(2);
    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it("closes a silent connection via watchdog and reconnects", () => {
    render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    act(() => {
      FakeWebSocket.instances[0].open();
    });

    act(() => {
      // open 后无任何服务端帧：90s 处看门狗判定静默（>75s）→ close(4000) → 1s 退避后重连
      vi.advanceTimersByTime(95_000);
    });

    expect(FakeWebSocket.instances[0].readyState).toBe(FakeWebSocket.CLOSED);
    expect(FakeWebSocket.instances.length).toBeGreaterThanOrEqual(2);
  });

  it("flushes synchronously when the queue hits the cap (hidden-tab rAF pause safety)", () => {
    render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    act(() => {
      const socket = FakeWebSocket.instances[0];
      socket.open();
      // 不跑 rAF（模拟隐藏标签页）连灌 1000 条 → 触顶时必须同步 flush 进 store
      for (let index = 0; index < 1000; index += 1) {
        socket.message({
          type: "price.tick",
          symbol: "EURUSD",
          bid: `1.${String(index).padStart(4, "0")}`,
          ask: "1.1701",
          mid: "1.17005",
          mark: "1.17005",
          ts: `2026-05-01T08:00:00.${String(index % 1000).padStart(3, "0")}Z`,
          source: "TM_QUOTE",
          stale: false,
          quoteStatus: "FRESH"
        });
      }
    });

    expect(useMarketStore.getState().quotes.EURUSD).toBeDefined();
    expect(useMarketStore.getState().quoteHistory.EURUSD?.length).toBeLessThanOrEqual(600);
  });

  it("flushes queued messages immediately when the tab becomes visible", () => {
    render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    act(() => {
      const socket = FakeWebSocket.instances[0];
      socket.open();
      socket.message({
        type: "price.tick",
        symbol: "EURUSD",
        bid: "1.1700",
        ask: "1.1701",
        mid: "1.17005",
        mark: "1.17005",
        ts: "2026-05-01T08:00:00Z",
        source: "TM_QUOTE",
        stale: false,
        quoteStatus: "FRESH"
      });
    });
    // rAF 未跑，队列里还压着这条
    expect(useMarketStore.getState().quotes.EURUSD).toBeUndefined();

    act(() => {
      document.dispatchEvent(new Event("visibilitychange")); // jsdom visibilityState 恒 visible
    });

    expect(useMarketStore.getState().quotes.EURUSD).toBeDefined();
  });

  it("keeps the existing socket when the access token rotates (refresh race hardening)", () => {
    const { rerender } = render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    act(() => {
      FakeWebSocket.instances[0].open();
    });

    // access token 15 分钟轮换（refresh）→ 不得重建连接（服务端仅握手时校验）
    rerender(createElement(MarketSocketHost, {
      token: "token-2",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });

    expect(FakeWebSocket.instances).toHaveLength(1);
    expect(FakeWebSocket.instances[0].readyState).toBe(FakeWebSocket.OPEN);
  });

  it("reconnects with the freshest token after an abnormal close during token refresh", () => {
    const { rerender } = render(createElement(MarketSocketHost, {
      token: "expired-token",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    const rejected = FakeWebSocket.instances[0];
    expect(rejected.url).toContain("token=expired-token");

    // 网关拒绝过期 token 握手（浏览器侧 1006）；同时 REST 401 已触发 refresh → token 轮换
    rerender(createElement(MarketSocketHost, {
      token: "fresh-token",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      rejected.closeWithCode(1006);
      vi.advanceTimersByTime(1_100); // 1s 退避后重连
    });

    expect(FakeWebSocket.instances).toHaveLength(2);
    expect(FakeWebSocket.instances[1].url).toContain("token=fresh-token");
  });

  it("ignores close events from stale sockets after logout and re-login", () => {
    const { rerender } = render(createElement(MarketSocketHost, {
      token: "token-1",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    const staleSocket = FakeWebSocket.instances[0];

    rerender(createElement(MarketSocketHost, {
      token: "",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    rerender(createElement(MarketSocketHost, {
      token: "token-2",
      watchedSymbols: ["EURUSD"],
      selectedSymbol: "EURUSD"
    }));
    act(() => {
      vi.advanceTimersByTime(50);
    });
    expect(FakeWebSocket.instances).toHaveLength(2);

    act(() => {
      staleSocket.closeWithCode(1006);
      vi.advanceTimersByTime(5_000);
    });

    expect(FakeWebSocket.instances).toHaveLength(2);
  });
});

function MarketSocketHost({
  token,
  watchedSymbols,
  selectedSymbol
}: {
  token: string;
  watchedSymbols: string[];
  selectedSymbol: string;
}) {
  useMarketSocket({
    token,
    watchedSymbols,
    selectedSymbol,
    klineInterval: "1m",
    onAuthExpired: () => undefined
  });
  return null;
}

class FakeWebSocket extends EventTarget {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSING = 2;
  static readonly CLOSED = 3;
  static instances: FakeWebSocket[] = [];

  readonly sent: string[] = [];
  readonly url: string;
  readyState = FakeWebSocket.CONNECTING;

  constructor(url: string) {
    super();
    this.url = url;
    FakeWebSocket.instances.push(this);
  }

  send(message: string) {
    this.sent.push(message);
  }

  close(code = 1000) {
    this.closeWithCode(code);
  }

  open() {
    this.readyState = FakeWebSocket.OPEN;
    this.dispatchEvent(new Event("open"));
  }

  closeWithCode(code: number) {
    this.readyState = FakeWebSocket.CLOSED;
    const event = new Event("close") as Event & { code: number };
    Object.defineProperty(event, "code", { value: code });
    this.dispatchEvent(event);
  }

  message(payload: unknown) {
    const event = new MessageEvent("message", {
      data: JSON.stringify(payload)
    });
    this.dispatchEvent(event);
  }
}
