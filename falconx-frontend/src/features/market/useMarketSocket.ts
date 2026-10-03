import { useCallback, useEffect, useMemo, useRef } from "react";
import { wsBaseUrl } from "../../lib/config";
import { toKlineChannel } from "./chartTimeframes";
import { useMarketStore } from "./marketStore";
import {
  buildSubscribeMessage,
  buildUnsubscribeMessage
} from "./marketSocket";
import type { KlineInterval, MarketChannel, MarketServerMessage } from "./marketTypes";

type UseMarketSocketOptions = {
  token: string | null | undefined;
  watchedSymbols: string[];
  selectedSymbol: string | null;
  klineInterval: KlineInterval | null;
  onAuthExpired: () => void;
};

const MAX_RECONNECT_DELAY_MS = 30_000;
const INITIAL_RECONNECT_DELAY_MS = 1_000;
const DEFERRED_CONNECT_DELAY_MS = 50;
const PRICE_SUBSCRIPTION_BATCH_SIZE = 100;
/**
 * 2026-06-04 P0：消息队列硬上限。浏览器对隐藏标签页完全暂停 rAF（唯一消费出口），
 * 不设限时后台挂几小时会堆积数十万条消息，切回前台一次性 reduce 造成主线程冻结
 * 数秒~数十秒。达到上限改为同步 flush（reduce 1000 条 ≪ 1 帧预算，后台偶发执行无感知）。
 */
const MAX_QUEUED_MESSAGES = 1_000;
/** 应用层心跳间隔（契约 §7：客户端可发 {type:"ping"}，服务端回 pong）。 */
const HEARTBEAT_INTERVAL_MS = 30_000;
/**
 * 2026-06-04 P1：静默看门狗阈值 = 2 个心跳周期 + 余量。半开连接（睡眠唤醒 / NAT 静默回收）
 * 浏览器可能长时间收不到 close 事件 → 价格定格但无重连。pong/stale 推送保证活连接必有流量，
 * 超时即主动 close(4000) 走既有指数退避重连路径。
 */
const SILENCE_TIMEOUT_MS = 75_000;

export function useMarketSocket({
  token,
  watchedSymbols,
  selectedSymbol,
  klineInterval,
  onAuthExpired
}: UseMarketSocketOptions): void {
  const setConnectionState = useMarketStore(
    (state) => state.setConnectionState
  );
  const applyMarketMessages = useMarketStore(
    (state) => state.applyMarketMessages
  );
  const requestCounterRef = useRef(0);
  const socketRef = useRef<WebSocket | null>(null);
  const socketGenerationRef = useRef(0);
  const connectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const flushFrameRef = useRef<number | null>(null);
  const messageQueueRef = useRef<MarketServerMessage[]>([]);
  const reconnectAttemptRef = useRef(0);
  const shouldReconnectRef = useRef(false);
  // P1 静默看门狗：最近一次收到任何服务端帧（含 pong）的时刻
  const lastMessageAtRef = useRef(0);
  // 2026-06-04 token 刷新竞态根治：连接生命周期只挂「是否登录」（hasToken），
  // access token 15 分钟轮换不再整条重建连接（服务端仅在握手时校验 token）；
  // 握手/重连时从 ref 读最新值，过期 token 的退避重试自然收敛到新 token。
  const tokenRef = useRef<string | null>(token ?? null);
  const latestPriceSymbolsRef = useRef<string[]>([]);
  const subscribedPriceSymbolsRef = useRef<string[]>([]);
  const latestKlineRef = useRef<KlineSubscription | null>(null);
  const subscribedKlineRef = useRef<KlineSubscription | null>(null);
  const onAuthExpiredRef = useRef(onAuthExpired);
  const setConnectionStateRef = useRef(setConnectionState);
  const enqueueMarketMessageRef = useRef<(message: MarketServerMessage) => void>(
    () => undefined
  );
  const replacePriceSubscriptionRef = useRef<(symbols: string[]) => void>(() => undefined);
  const replaceKlineSubscriptionRef = useRef<(next: KlineSubscription | null) => void>(
    () => undefined
  );
  const priceSymbols = useMemo(
    () => [
      ...new Set(
        [...watchedSymbols, selectedSymbol].filter(
          (symbol): symbol is string => Boolean(symbol)
        )
      )
    ],
    [selectedSymbol, watchedSymbols]
  );
  const klineChannel = klineInterval ? toKlineChannel(klineInterval) : null;

  const nextRequestId = useCallback(() => {
    requestCounterRef.current += 1;
    return `market-${requestCounterRef.current}`;
  }, []);

  const sendJson = useCallback((message: unknown) => {
    const socket = socketRef.current;
    if (socket?.readyState !== WebSocket.OPEN) {
      return;
    }

    socket.send(JSON.stringify(message));
  }, []);

  const flushQueuedMessages = useCallback(() => {
    flushFrameRef.current = null;
    const messages = messageQueueRef.current;
    messageQueueRef.current = [];
    if (messages.length) {
      applyMarketMessages(messages);
    }
  }, [applyMarketMessages]);

  const enqueueMarketMessage = useCallback(
    (message: MarketServerMessage) => {
      messageQueueRef.current.push(message);
      // P0：队列触顶（后台标签页 rAF 暂停时的兜底）→ 立即同步 flush，杜绝无界堆积
      if (messageQueueRef.current.length >= MAX_QUEUED_MESSAGES) {
        if (flushFrameRef.current !== null) {
          cancelAnimationFrame(flushFrameRef.current);
        }
        flushQueuedMessages();
        return;
      }
      if (flushFrameRef.current === null) {
        flushFrameRef.current = requestAnimationFrame(flushQueuedMessages);
      }
    },
    [flushQueuedMessages]
  );

  // P0：切回前台立即 flush 积压（rAF 恢复前的首帧延迟也消掉），避免可见瞬间数据滞后
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState !== "visible") {
        return;
      }
      if (flushFrameRef.current !== null) {
        cancelAnimationFrame(flushFrameRef.current);
      }
      flushQueuedMessages();
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, [flushQueuedMessages]);

  const replacePriceSubscription = useCallback(
    (nextSymbols: string[]) => {
      const previousSymbols = subscribedPriceSymbolsRef.current;
      if (sameStringList(previousSymbols, nextSymbols)) {
        return;
      }

      const nextSymbolSet = new Set(nextSymbols);
      const previousSymbolSet = new Set(previousSymbols);
      const removedSymbols = previousSymbols.filter((symbol) => !nextSymbolSet.has(symbol));
      const addedSymbols = nextSymbols.filter((symbol) => !previousSymbolSet.has(symbol));

      if (removedSymbols.length) {
        for (const symbols of chunkPriceSubscriptionSymbols(removedSymbols)) {
          sendJson(buildUnsubscribeMessage(symbols, nextRequestId(), ["price.tick"]));
        }
      }
      if (addedSymbols.length) {
        for (const symbols of chunkPriceSubscriptionSymbols(addedSymbols)) {
          sendJson(buildSubscribeMessage(symbols, nextRequestId(), ["price.tick"]));
        }
      }
      subscribedPriceSymbolsRef.current = nextSymbols;
    },
    [nextRequestId, sendJson]
  );

  const replaceKlineSubscription = useCallback(
    (next: KlineSubscription | null) => {
      const previous = subscribedKlineRef.current;
      if (
        previous?.symbol === next?.symbol &&
        previous?.channel === next?.channel
      ) {
        return;
      }

      if (previous) {
        sendJson(buildUnsubscribeMessage(previous.symbol, nextRequestId(), [previous.channel]));
      }
      if (next) {
        sendJson(buildSubscribeMessage(next.symbol, nextRequestId(), [next.channel]));
      }
      subscribedKlineRef.current = next;
    },
    [nextRequestId, sendJson]
  );

  const clearReconnectTimer = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
  }, []);

  const clearConnectTimer = useCallback(() => {
    if (connectTimerRef.current) {
      clearTimeout(connectTimerRef.current);
      connectTimerRef.current = null;
    }
  }, []);

  useEffect(() => {
    tokenRef.current = token ?? null;
  }, [token]);

  useEffect(() => {
    onAuthExpiredRef.current = onAuthExpired;
  }, [onAuthExpired]);

  useEffect(() => {
    setConnectionStateRef.current = setConnectionState;
  }, [setConnectionState]);

  useEffect(() => {
    enqueueMarketMessageRef.current = enqueueMarketMessage;
  }, [enqueueMarketMessage]);

  useEffect(() => {
    replacePriceSubscriptionRef.current = replacePriceSubscription;
  }, [replacePriceSubscription]);

  useEffect(() => {
    replaceKlineSubscriptionRef.current = replaceKlineSubscription;
  }, [replaceKlineSubscription]);

  useEffect(() => {
    latestPriceSymbolsRef.current = priceSymbols;
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      replacePriceSubscription(priceSymbols);
    }
  }, [priceSymbols, replacePriceSubscription]);

  useEffect(() => {
    latestKlineRef.current =
      selectedSymbol && klineChannel
        ? { symbol: selectedSymbol, channel: klineChannel }
        : null;
    if (socketRef.current?.readyState === WebSocket.OPEN) {
      replaceKlineSubscription(latestKlineRef.current);
    }
  }, [klineChannel, replaceKlineSubscription, selectedSymbol]);

  const hasToken = Boolean(token);

  useEffect(() => {
    if (!hasToken) {
      shouldReconnectRef.current = false;
      socketGenerationRef.current += 1;
      clearConnectTimer();
      clearReconnectTimer();
      socketRef.current?.close(1000);
      socketRef.current = null;
      subscribedPriceSymbolsRef.current = [];
      subscribedKlineRef.current = null;
      setConnectionStateRef.current("idle");
      return;
    }

    shouldReconnectRef.current = true;

    const connect = () => {
      connectTimerRef.current = null;
      clearReconnectTimer();
      setConnectionStateRef.current(
        reconnectAttemptRef.current === 0 ? "connecting" : "reconnecting"
      );
      subscribedPriceSymbolsRef.current = [];
      subscribedKlineRef.current = null;

      // 握手时刻读最新 token（refresh 后 ref 已更新）；连接生命周期不随轮换重建
      const currentToken = tokenRef.current;
      if (!currentToken) {
        return;
      }
      const socketGeneration = socketGenerationRef.current + 1;
      socketGenerationRef.current = socketGeneration;
      const socket = new WebSocket(
        `${wsBaseUrl}/ws/v1/market?token=${encodeURIComponent(currentToken)}`
      );
      socketRef.current = socket;
      const isCurrentSocket = () =>
        socketRef.current === socket && socketGenerationRef.current === socketGeneration;

      socket.addEventListener("open", () => {
        if (!isCurrentSocket()) {
          return;
        }
        reconnectAttemptRef.current = 0;
        lastMessageAtRef.current = Date.now();
        setConnectionStateRef.current("open");
        replacePriceSubscriptionRef.current(latestPriceSymbolsRef.current);
        replaceKlineSubscriptionRef.current(latestKlineRef.current);
      });

      socket.addEventListener("message", (event) => {
        if (!isCurrentSocket()) {
          return;
        }
        lastMessageAtRef.current = Date.now();
        if (typeof event.data !== "string") {
          return;
        }

        try {
          enqueueMarketMessageRef.current(
            stampReceivedAt(JSON.parse(event.data) as MarketServerMessage)
          );
        } catch {
          setConnectionStateRef.current("error");
        }
      });

      socket.addEventListener("error", () => {
        if (!isCurrentSocket()) {
          return;
        }
        setConnectionStateRef.current("error");
      });

      socket.addEventListener("close", (event) => {
        if (!isCurrentSocket()) {
          return;
        }
        subscribedPriceSymbolsRef.current = [];
        subscribedKlineRef.current = null;

        if (!shouldReconnectRef.current) {
          setConnectionStateRef.current("closed");
          return;
        }

        if (event.code === 1008) {
          shouldReconnectRef.current = false;
          setConnectionStateRef.current("auth_expired");
          onAuthExpiredRef.current();
          return;
        }

        if (event.code === 1000) {
          setConnectionStateRef.current("closed");
          return;
        }

        const delay = Math.min(
          INITIAL_RECONNECT_DELAY_MS * 2 ** reconnectAttemptRef.current,
          MAX_RECONNECT_DELAY_MS
        );
        reconnectAttemptRef.current += 1;
        setConnectionStateRef.current("reconnecting");
        reconnectTimerRef.current = setTimeout(connect, delay);
      });
    };

    connectTimerRef.current = setTimeout(connect, DEFERRED_CONNECT_DELAY_MS);

    // P1：应用层心跳 + 静默看门狗。每 30s 发 {type:"ping"}（契约 §7，服务端回 pong）；
    // 超过 SILENCE_TIMEOUT_MS 没收到任何帧 = 半开连接（睡眠唤醒/NAT 回收后浏览器
    // 收不到 close 事件），主动 close(4000) 触发既有指数退避重连路径。
    // 隐藏标签页下 interval 被浏览器节流到 ≥1/min，探测变慢但语义不变。
    const heartbeatTimer = setInterval(() => {
      const socket = socketRef.current;
      if (socket?.readyState !== WebSocket.OPEN) {
        return;
      }
      if (Date.now() - lastMessageAtRef.current > SILENCE_TIMEOUT_MS) {
        socket.close(4000, "silence-timeout");
        return;
      }
      socket.send(JSON.stringify({ type: "ping", ts: new Date().toISOString() }));
    }, HEARTBEAT_INTERVAL_MS);

    return () => {
      shouldReconnectRef.current = false;
      socketGenerationRef.current += 1;
      clearInterval(heartbeatTimer);
      clearConnectTimer();
      clearReconnectTimer();
      if (flushFrameRef.current !== null) {
        cancelAnimationFrame(flushFrameRef.current);
        flushFrameRef.current = null;
      }
      messageQueueRef.current = [];
      socketRef.current?.close(1000);
      socketRef.current = null;
      subscribedPriceSymbolsRef.current = [];
      subscribedKlineRef.current = null;
    };
  }, [clearConnectTimer, clearReconnectTimer, hasToken]);
}

type KlineSubscription = {
  symbol: string;
  channel: MarketChannel;
};

export function chunkPriceSubscriptionSymbols(symbols: string[]): string[][] {
  const chunks: string[][] = [];
  for (let index = 0; index < symbols.length; index += PRICE_SUBSCRIPTION_BATCH_SIZE) {
    chunks.push(symbols.slice(index, index + PRICE_SUBSCRIPTION_BATCH_SIZE));
  }
  return chunks;
}

function sameStringList(left: string[], right: string[]): boolean {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function stampReceivedAt(message: MarketServerMessage): MarketServerMessage {
  if (
    message &&
    typeof message === "object" &&
    "type" in message &&
    message.type === "price.tick"
  ) {
    return {
      ...message,
      receivedAt: new Date().toISOString()
    };
  }

  return message;
}
