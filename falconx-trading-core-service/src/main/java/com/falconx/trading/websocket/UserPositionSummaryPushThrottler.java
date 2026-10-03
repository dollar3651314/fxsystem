package com.falconx.trading.websocket;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 单用户「总未实现盈亏」推送节流器（per-user 500ms）。
 *
 * <p>与 admin AdminPositionSummaryPushThrottler 的全局 500ms 不同：用户级 push 每个用户独立计时，
 * 避免高频用户互相干扰；同时保持单用户最低 500ms 一次的 push 频率（每秒约 2 次更新足够 UI 体感实时）。
 */
@Component
public class UserPositionSummaryPushThrottler {

    private static final long MIN_INTERVAL_MS = 500L;

    private final ConcurrentHashMap<Long, Long> lastPushAt = new ConcurrentHashMap<>();

    public boolean shouldPush(long userId) {
        long now = System.currentTimeMillis();
        Long last = lastPushAt.get(userId);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return false;
        }
        return lastPushAt.compute(userId, (k, prev) -> {
            if (prev != null && now - prev < MIN_INTERVAL_MS) return prev;
            return now;
        }) == now;
    }
}
