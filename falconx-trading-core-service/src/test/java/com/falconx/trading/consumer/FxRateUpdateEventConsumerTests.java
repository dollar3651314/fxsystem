package com.falconx.trading.consumer;

import com.falconx.market.contract.FxRateSnapshotPayload;
import com.falconx.trading.service.FxRateService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * STAGE-14B Task 3：FxRateUpdateEventConsumer 单元测试。
 */
class FxRateUpdateEventConsumerTests {

    @Test
    void consume_delegates_to_fx_rate_service() {
        FxRateService fxRateService = mock(FxRateService.class);
        FxRateUpdateEventConsumer consumer = new FxRateUpdateEventConsumer(fxRateService);
        FxRateSnapshotPayload payload = new FxRateSnapshotPayload(
                "EUR", "USD", new BigDecimal("1.0800"), 1_000_000L, "LP1", "EURUSD");

        consumer.consume("evt-001", payload);

        verify(fxRateService).acceptUpdate("EUR", "USD", new BigDecimal("1.0800"), 1_000_000L);
    }

    @Test
    void consume_exception_is_isolated_not_propagated() {
        FxRateService fxRateService = mock(FxRateService.class);
        FxRateUpdateEventConsumer consumer = new FxRateUpdateEventConsumer(fxRateService);
        FxRateSnapshotPayload payload = new FxRateSnapshotPayload(
                "GBP", "USD", new BigDecimal("1.2500"), 2_000_000L, "LP2", "GBPUSD");
        doThrow(new RuntimeException("internal error"))
                .when(fxRateService).acceptUpdate("GBP", "USD", new BigDecimal("1.2500"), 2_000_000L);

        // Must not throw — single message failure must not stop the listener
        consumer.consume("evt-002", payload);
    }
}
