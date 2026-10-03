package com.falconx.market.health;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.falconx.market.provider.SocketIoLpMarketQuoteProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * {@link LpSocketIoHealthIndicator} 单元测试，覆盖 未连接 / 连接但无近期报价 / 连接且近期报价 三态。
 */
class LpSocketIoHealthIndicatorTests {

    @Test
    void shouldReportDownWhenDisconnected() {
        SocketIoLpMarketQuoteProvider provider = mock(SocketIoLpMarketQuoteProvider.class);
        when(provider.isLpConnected()).thenReturn(false);

        Health health = new LpSocketIoHealthIndicator(provider).health();

        Assertions.assertEquals(Status.DOWN, health.getStatus());
        Assertions.assertEquals("disconnected", health.getDetails().get("reason"));
    }

    @Test
    void shouldReportDownWhenConnectedButNeverReceivedQuote() {
        SocketIoLpMarketQuoteProvider provider = mock(SocketIoLpMarketQuoteProvider.class);
        when(provider.isLpConnected()).thenReturn(true);
        when(provider.lastQuoteEpochMillis()).thenReturn(0L);

        Health health = new LpSocketIoHealthIndicator(provider).health();

        Assertions.assertEquals(Status.DOWN, health.getStatus());
        Assertions.assertEquals("no-recent-quote", health.getDetails().get("reason"));
    }

    @Test
    void shouldReportDownWhenConnectedButQuoteStale() {
        SocketIoLpMarketQuoteProvider provider = mock(SocketIoLpMarketQuoteProvider.class);
        when(provider.isLpConnected()).thenReturn(true);
        // 距今 > 60s 阈值。
        when(provider.lastQuoteEpochMillis()).thenReturn(System.currentTimeMillis() - 120_000L);

        Health health = new LpSocketIoHealthIndicator(provider).health();

        Assertions.assertEquals(Status.DOWN, health.getStatus());
        Assertions.assertEquals("no-recent-quote", health.getDetails().get("reason"));
    }

    @Test
    void shouldReportUpWhenConnectedWithRecentQuote() {
        SocketIoLpMarketQuoteProvider provider = mock(SocketIoLpMarketQuoteProvider.class);
        when(provider.isLpConnected()).thenReturn(true);
        when(provider.lastQuoteEpochMillis()).thenReturn(System.currentTimeMillis() - 1_000L);

        Health health = new LpSocketIoHealthIndicator(provider).health();

        Assertions.assertEquals(Status.UP, health.getStatus());
        Assertions.assertEquals(Boolean.TRUE, health.getDetails().get("connected"));
    }
}
