package com.falconx.trading.websocket;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * STAGE-2-REALTIME-DATA Phase 1：PnL 推送节流器。
 *
 * <p>按 symbol 100ms 节流：同一 symbol 在 100ms 窗口内最多触发一次 PnL 广播。
 * 热门品种 BTCUSDT 100 ticks/s → 节流后 10 events/s/symbol，
 * 同 symbol 多 user 一起推一次，扇出由 SessionRegistry.broadcast 完成。
 */
@Component
public class PositionPnlPushThrottler {

    private static final long MIN_INTERVAL_MS = 100L;

    private final ConcurrentHashMap<String, Long> lastPushAtBySymbol = new ConcurrentHashMap<>();

    /**
     * 检查并占用 symbol 的下一个推送窗口。
     *
     * @return true → 允许推送并已记录时间；false → 在节流窗口内，跳过本次
     */
    public boolean shouldPush(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastPushAtBySymbol.get(symbol);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return false;
        }
        // CAS：避免多线程同时通过窗口
        return lastPushAtBySymbol.compute(symbol, (key, prev) -> {
            if (prev != null && now - prev < MIN_INTERVAL_MS) {
                return prev;
            }
            return now;
        }) == now;
    }
}
