package com.falconx.trading.consumer;

import com.falconx.infrastructure.kafka.KafkaEventMessageSupport;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.application.SymbolPartitionedPriceTickExecutor;
import com.falconx.trading.dto.PriceTickProcessingResult;
import com.falconx.trading.engine.QuoteDrivenEngine;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 市场价格事件消费者。
 *
 * <p>该消费者是 `market-service -> trading-core-service` 的高频事件入口。
 * 当前阶段仅负责把标准价格 payload 交给报价驱动引擎，不对高频 tick 写 `t_inbox`。
 */
@Component
public class MarketPriceTickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketPriceTickEventConsumer.class);

    private final QuoteDrivenEngine quoteDrivenEngine;
    private final SymbolPartitionedPriceTickExecutor tradingPriceTickExecutor;
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Autowired
    public MarketPriceTickEventConsumer(QuoteDrivenEngine quoteDrivenEngine,
                                        @Qualifier("tradingPriceTickExecutor")
                                        SymbolPartitionedPriceTickExecutor tradingPriceTickExecutor) {
        this(quoteDrivenEngine, tradingPriceTickExecutor, null);
    }

    MarketPriceTickEventConsumer(QuoteDrivenEngine quoteDrivenEngine,
                                 @Qualifier("tradingPriceTickExecutor")
                                 SymbolPartitionedPriceTickExecutor tradingPriceTickExecutor,
                                 MeterRegistry meterRegistry) {
        this.quoteDrivenEngine = quoteDrivenEngine;
        this.tradingPriceTickExecutor = tradingPriceTickExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 消费价格 tick 事件。
     *
     * @param eventId 事件 ID
     * @param payload 标准价格 payload
     */
    public void consume(String eventId, MarketPriceTickEventPayload payload) {
        String traceId = MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY);
        ClassLoader callerClassLoader = Thread.currentThread().getContextClassLoader();
        long startedAtNanos = System.nanoTime();
        recordReceived(payload.symbol());
        try {
            tradingPriceTickExecutor.submit(
                    payload.symbol(),
                    () -> runOnManagedThread(eventId, payload, traceId, callerClassLoader, startedAtNanos)
            ).get();
            recordDuration(payload.symbol(), startedAtNanos);
            recordCompleted(payload.symbol(), "success");
        } catch (InterruptedException exception) {
            recordDuration(payload.symbol(), startedAtNanos);
            recordFailure(payload.symbol(), "interrupted");
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while processing market price tick", exception);
        } catch (ExecutionException exception) {
            recordDuration(payload.symbol(), startedAtNanos);
            recordFailure(payload.symbol(), "execution");
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to process market price tick", cause);
        }
    }

    private void runOnManagedThread(String eventId,
                                    MarketPriceTickEventPayload payload,
                                    String traceId,
                                    ClassLoader callerClassLoader,
                                    long submittedAtNanos) {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        KafkaEventMessageSupport.bindTraceId(traceId);
        Thread.currentThread().setContextClassLoader(callerClassLoader);
        try {
            recordQueueDelay(payload.symbol(), submittedAtNanos);
            // 2026-05-20 perf: per-tick 高频路径，从 INFO 降到 DEBUG 避免 ~10K/s 写日志
            log.debug("trading.consumer.market.price.tick eventId={} symbol={} ts={}",
                    eventId,
                    payload.symbol(),
                    payload.ts());
            if (payload.stale()) {
                log.warn("trading.consumer.market.price.tick.stale eventId={} symbol={} ts={} action=snapshot-only",
                        eventId,
                        payload.symbol(),
                        payload.ts());
            }
            long engineStartedAtNanos = System.nanoTime();
            try {
                PriceTickProcessingResult result = quoteDrivenEngine.processTick(payload);
                recordEngineDuration(payload.symbol(), "success", engineStartedAtNanos);
                recordTriggeredActions(payload.symbol(), result);
            } catch (RuntimeException exception) {
                recordEngineDuration(payload.symbol(), "failure", engineStartedAtNanos);
                throw exception;
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
            KafkaEventMessageSupport.clearTraceId();
        }
    }

    private void recordReceived(String symbol) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "falconx.trading.tick.consumer.received",
                "symbol", normalizeMetricSymbol(symbol)
        ).increment();
    }

    private void recordCompleted(String symbol, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "falconx.trading.tick.consumer.completed",
                "symbol", normalizeMetricSymbol(symbol),
                "outcome", outcome
        ).increment();
    }

    private void recordDuration(String symbol, long startedAtNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer(
                "falconx.trading.tick.consumer.duration",
                "symbol", normalizeMetricSymbol(symbol)
        ).record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    private void recordQueueDelay(String symbol, long submittedAtNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer(
                "falconx.trading.tick.queue.delay",
                "symbol", normalizeMetricSymbol(symbol)
        ).record(System.nanoTime() - submittedAtNanos, TimeUnit.NANOSECONDS);
    }

    private void recordEngineDuration(String symbol, String outcome, long startedAtNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.timer(
                "falconx.trading.tick.engine.duration",
                "symbol", normalizeMetricSymbol(symbol),
                "outcome", outcome
        ).record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    private void recordTriggeredActions(String symbol, PriceTickProcessingResult result) {
        if (meterRegistry == null || result == null || result.triggeredActions() <= 0) {
            return;
        }
        meterRegistry.counter(
                "falconx.trading.tick.triggered.actions",
                "symbol", normalizeMetricSymbol(symbol)
        ).increment(result.triggeredActions());
    }

    private void recordFailure(String symbol, String reason) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(
                "falconx.trading.tick.consumer.failures",
                "symbol", normalizeMetricSymbol(symbol),
                "reason", reason
        ).increment();
    }

    private static String normalizeMetricSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? "UNKNOWN" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
