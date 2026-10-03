package com.falconx.gateway.websocket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * gateway WebSocket 连接计数器。
 *
 * <p>一期固定按 gateway 单实例维度控制同一用户的并发连接数，
 * 用于落实 `/ws/v1/market` 的“单用户最多 5 个并发连接”约束。
 */
@Component
public class GatewayMarketWebSocketSessionRegistry {

    private final ConcurrentMap<String, AtomicInteger> activeConnectionsByUser = new ConcurrentHashMap<>();

    public boolean tryAcquire(String userId, int limit) {
        AtomicInteger counter = activeConnectionsByUser.computeIfAbsent(userId, ignored -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= limit) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        activeConnectionsByUser.computeIfPresent(userId, (ignored, counter) -> {
            int next = Math.max(counter.decrementAndGet(), 0);
            return next == 0 ? null : counter;
        });
    }
}
