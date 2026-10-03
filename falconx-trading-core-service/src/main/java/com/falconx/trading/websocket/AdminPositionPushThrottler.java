package com.falconx.trading.websocket;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 管理端持仓 PnL 推送节流器（按 symbol 200ms，与 AdminExposurePushThrottler 同步节奏）。
 *
 * <p>QuoteDrivenEngine 每条 tick 同时调用 user/admin 两套 push，节流粒度独立：
 * user = 100ms（PositionPnlPushThrottler），admin = 200ms（本类）。
 */
@Component
public class AdminPositionPushThrottler {

    private static final long MIN_INTERVAL_MS = 200L;

    private final ConcurrentHashMap<String, Long> lastPushAtBySymbol = new ConcurrentHashMap<>();

    public boolean shouldPush(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastPushAtBySymbol.get(symbol);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return false;
        }
        return lastPushAtBySymbol.compute(symbol, (key, prev) -> {
            if (prev != null && now - prev < MIN_INTERVAL_MS) {
                return prev;
            }
            return now;
        }) == now;
    }
}
