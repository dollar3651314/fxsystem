package com.falconx.trading.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.SymbolPartitionedPriceTickExecutor;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.QuoteDrivenEngine;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * MarketPriceTickEventConsumer 线程切换测试。
 */
class MarketPriceTickEventConsumerTests {

    @Test
    void shouldSwitchToManagedExecutorThreadAndKeepTraceIdAcrossExecution() throws Exception {
        QuoteDrivenEngine quoteDrivenEngine = mock(QuoteDrivenEngine.class);
        SymbolPartitionedPriceTickExecutor executorService = new SymbolPartitionedPriceTickExecutor(1);
        MarketPriceTickEventConsumer consumer = new MarketPriceTickEventConsumer(quoteDrivenEngine, executorService);
        AtomicReference<String> executionThreadName = new AtomicReference<>();
        AtomicReference<String> executionTraceId = new AtomicReference<>();
        AtomicReference<String> callerTraceIdAfterConsume = new AtomicReference<>();
        String listenerThreadName = Thread.currentThread().getName();
        String expectedTraceId = "11111111111111111111111111111111";

        when(quoteDrivenEngine.processTick(any(MarketPriceTickEventPayload.class))).thenAnswer(invocation -> {
            executionThreadName.set(Thread.currentThread().getName());
            executionTraceId.set(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
            return new PriceTickProcessingResult(new TradingQuoteSnapshot(
                    "BTCUSDT",
                    new BigDecimal("9990.00000000"),
                    new BigDecimal("10000.00000000"),
                    new BigDecimal("9995.00000000"),
                    OffsetDateTime.now(),
                    "consumer-test",
                    false
            ), 0);
        });

        try {
            MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, expectedTraceId);
            consumer.consume("event-001", new MarketPriceTickEventPayload(
                    "BTCUSDT",
                    new BigDecimal("9990.00000000"),
                    new BigDecimal("10000.00000000"),
                    new BigDecimal("9995.00000000"),
                    new BigDecimal("9995.00000000"),
                    OffsetDateTime.now(),
                    "consumer-test",
                    false
            ));
            callerTraceIdAfterConsume.set(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
            executorService.shutdown();
            Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertNotNull(executionThreadName.get());
        Assertions.assertTrue(executionThreadName.get().startsWith("trading-price-tick-"));
        Assertions.assertNotEquals(listenerThreadName, executionThreadName.get());
        Assertions.assertEquals(expectedTraceId, executionTraceId.get());
        Assertions.assertEquals(expectedTraceId, callerTraceIdAfterConsume.get());
        verify(quoteDrivenEngine).processTick(any(MarketPriceTickEventPayload.class));
    }

    @Test
    void shouldRecordTickProcessingDurationMetric() throws Exception {
        QuoteDrivenEngine quoteDrivenEngine = mock(QuoteDrivenEngine.class);
        SymbolPartitionedPriceTickExecutor executorService = new SymbolPartitionedPriceTickExecutor(1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MarketPriceTickEventConsumer consumer = new MarketPriceTickEventConsumer(
                quoteDrivenEngine,
                executorService,
                meterRegistry
        );
        MarketPriceTickEventPayload payload = new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("9990.00000000"),
                new BigDecimal("10000.00000000"),
                new BigDecimal("9995.00000000"),
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "consumer-test",
                false
        );
        when(quoteDrivenEngine.processTick(any(MarketPriceTickEventPayload.class))).thenReturn(
                new PriceTickProcessingResult(new TradingQuoteSnapshot(
                        "BTCUSDT",
                        new BigDecimal("9990.00000000"),
                        new BigDecimal("10000.00000000"),
                        new BigDecimal("9995.00000000"),
                        OffsetDateTime.now(),
                        "consumer-test",
                        false
                ), 2)
        );

        try {
            consumer.consume("event-002", payload);
        } finally {
            executorService.shutdown();
            Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertEquals(1, meterRegistry.timer(
                "falconx.trading.tick.consumer.duration",
                "symbol", "BTCUSDT"
        ).count());
        Assertions.assertEquals(1, meterRegistry.counter(
                "falconx.trading.tick.consumer.received",
                "symbol", "BTCUSDT"
        ).count());
        Assertions.assertEquals(1, meterRegistry.counter(
                "falconx.trading.tick.consumer.completed",
                "symbol", "BTCUSDT",
                "outcome", "success"
        ).count());
        Assertions.assertEquals(1, meterRegistry.timer(
                "falconx.trading.tick.queue.delay",
                "symbol", "BTCUSDT"
        ).count());
        Assertions.assertEquals(1, meterRegistry.timer(
                "falconx.trading.tick.engine.duration",
                "symbol", "BTCUSDT",
                "outcome", "success"
        ).count());
        Assertions.assertEquals(2, meterRegistry.counter(
                "falconx.trading.tick.triggered.actions",
                "symbol", "BTCUSDT"
        ).count());
    }
}
