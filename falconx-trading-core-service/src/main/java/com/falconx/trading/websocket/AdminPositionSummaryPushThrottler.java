package com.falconx.trading.websocket;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 管理端持仓汇总推送节流器（全局 500ms）。
 *
 * <p>与 AdminPositionPushThrottler（200ms/symbol）不同，汇总是平台级单一数据，
 * 不区分 symbol。500ms 平衡实时性与广播开销。
 */
@Component
public class AdminPositionSummaryPushThrottler {

    private static final long MIN_INTERVAL_MS = 500L;

    private final AtomicLong lastPushAtMs = new AtomicLong(0);

    public boolean shouldPush() {
        long now = System.currentTimeMillis();
        long last = lastPushAtMs.get();
        if (now - last < MIN_INTERVAL_MS) {
            return false;
        }
        return lastPushAtMs.compareAndSet(last, now);
    }
}
