package com.falconx.trading.websocket;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * STAGE-2-REALTIME-DATA Phase 2：管理端 exposure 推送节流器。
 *
 * <p>按 symbol 200ms 节流（任务卡 §1 表格 G）。
 */
@Component
public class AdminExposurePushThrottler {

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
