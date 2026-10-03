package com.falconx.market.service.impl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控制时间推进的 Clock，仅供单元测试使用。
 *
 * <p>初始时间为创建时的系统时间，通过 {@link #advance(long)} 手动前进，
 * 用于模拟 stale 超时等时间相关场景。
 */
class FakeClock extends Clock {

    private long current = System.currentTimeMillis();

    /**
     * 返回当前虚拟时间（毫秒），与 {@link #millis()} 等价，语义更清晰。
     */
    public long nowMillis() {
        return current;
    }

    /**
     * 将虚拟时钟前进 {@code ms} 毫秒。
     */
    public void advance(long ms) {
        current += ms;
    }

    @Override
    public long millis() {
        return current;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(current);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
