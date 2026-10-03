package com.falconx.trading.consumer;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.falconx.domain.enums.ChainType;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.market.contract.event.MarketKlineUpdateEventPayload;
import com.falconx.market.contract.event.MarketPriceTickEventPayload;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.wallet.contract.event.WalletDepositConfirmedEventPayload;
import com.falconx.wallet.contract.event.WalletDepositReversedEventPayload;
import com.falconx.market.contract.FxRateSnapshotPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * `TradingKafkaEventListener` 单元测试。
 *
 * <p>该测试确认 trading-core-service 的 Kafka listener 会完成 JSON 反序列化并委托给领域消费者。
 */
class TradingKafkaEventListenerTests {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldDelegateMarketPriceTick() throws Exception {
        MarketPriceTickEventConsumer marketConsumer = mock(MarketPriceTickEventConsumer.class);
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                mock(MarketKlineUpdateEventConsumer.class),
                marketConsumer,
                mock(TradingManagedKafkaExecutionSupport.class),
                mock(WalletDepositConfirmedEventConsumer.class),
                mock(WalletDepositReversedEventConsumer.class),
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );
        MarketPriceTickEventPayload payload = new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("100"),
                new BigDecimal("101"),
                new BigDecimal("100.5"),
                new BigDecimal("100.4"),
                OffsetDateTime.parse("2026-04-17T12:00:00Z"),
                "unit-test",
                false
        );

        listener.onMarketPriceTick(
                objectMapper.writeValueAsString(payload),
                "evt-50001",
                "1234567890abcdef1234567890abcdef"
        );

        verify(marketConsumer).consume(eq("evt-50001"), eq(payload));
        verifyNoMoreInteractions(marketConsumer);
    }

    @Test
    void shouldDelegateMarketPriceTickWithNumericTimestamp() {
        MarketPriceTickEventConsumer marketConsumer = mock(MarketPriceTickEventConsumer.class);
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                mock(MarketKlineUpdateEventConsumer.class),
                marketConsumer,
                mock(TradingManagedKafkaExecutionSupport.class),
                mock(WalletDepositConfirmedEventConsumer.class),
                mock(WalletDepositReversedEventConsumer.class),
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );
        // 10-arg 构造器：硬编码 JSON 没有 quoteStatus / qualityReason 字段，反序列化为 null；
        // 用 8-arg 二级构造器会自动填 "FRESH" 不匹配实际反序列化结果。
        MarketPriceTickEventPayload payload = new MarketPriceTickEventPayload(
                "BTCUSDT",
                new BigDecimal("100"),
                new BigDecimal("101"),
                new BigDecimal("100.5"),
                new BigDecimal("100.4"),
                OffsetDateTime.parse("2026-04-17T12:00:00Z"),
                "unit-test",
                false,
                null,
                null
        );

        listener.onMarketPriceTick(
                """
                        {
                          "symbol": "BTCUSDT",
                          "bid": 100,
                          "ask": 101,
                          "mid": 100.5,
                          "mark": 100.4,
                          "ts": 1776427200,
                          "source": "unit-test",
                          "stale": false
                        }
                        """,
                "evt-50002",
                "1234567890abcdef1234567890abcdef"
        );

        verify(marketConsumer).consume(eq("evt-50002"), eq(payload));
        verifyNoMoreInteractions(marketConsumer);
    }

    @Test
    void shouldDelegateMarketKlineUpdate() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("trading-kafka-low-frequency-", 0)
                .factory());
        MarketKlineUpdateEventPayload payload = new MarketKlineUpdateEventPayload(
                "BTCUSDT",
                "1m",
                new BigDecimal("100"),
                new BigDecimal("110"),
                new BigDecimal("95"),
                new BigDecimal("108"),
                new BigDecimal("2.5"),
                OffsetDateTime.parse("2026-04-20T12:00:00Z"),
                OffsetDateTime.parse("2026-04-20T12:00:59Z"),
                true
        );
        String payloadJson = objectMapper.writeValueAsString(payload);
        String listenerThreadName = Thread.currentThread().getName();
        String expectedTraceId = "1234567890abcdef1234567890abcdef";
        AtomicReference<String> executionThreadName = new AtomicReference<>();
        AtomicReference<String> executionTraceId = new AtomicReference<>();
        MarketKlineUpdateEventConsumer marketKlineConsumer = mock(MarketKlineUpdateEventConsumer.class);
        doAnswer(invocation -> {
            executionThreadName.set(Thread.currentThread().getName());
            executionTraceId.set(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
            return null;
        }).when(marketKlineConsumer).consume(any(String.class), any(MarketKlineUpdateEventPayload.class), any(String.class));
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                marketKlineConsumer,
                mock(MarketPriceTickEventConsumer.class),
                new TradingManagedKafkaExecutionSupport(executorService),
                mock(WalletDepositConfirmedEventConsumer.class),
                mock(WalletDepositReversedEventConsumer.class),
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );

        try {
            listener.onMarketKlineUpdate(
                    payloadJson,
                    "evt-50001-kline",
                    expectedTraceId
            );
        } finally {
            executorService.shutdown();
            Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertNotNull(executionThreadName.get());
        Assertions.assertTrue(executionThreadName.get().startsWith("trading-kafka-low-frequency-"));
        Assertions.assertNotEquals(listenerThreadName, executionThreadName.get());
        Assertions.assertEquals(expectedTraceId, executionTraceId.get());
        Assertions.assertNull(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
        verify(marketKlineConsumer).consume(eq("evt-50001-kline"), eq(payload), eq(payloadJson));
        verifyNoMoreInteractions(marketKlineConsumer);
    }

    @Test
    void shouldDelegateWalletDepositConfirmed() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("trading-kafka-low-frequency-", 0)
                .factory());
        WalletDepositConfirmedEventPayload payload = new WalletDepositConfirmedEventPayload(
                77001L,
                9001L,
                ChainType.ETH,
                "USDT",
                "0xhash",
                "0xfrom",
                "0xto",
                new BigDecimal("11.5"),
                12,
                12,
                OffsetDateTime.parse("2026-04-17T12:00:00Z")
        );
        String listenerThreadName = Thread.currentThread().getName();
        String expectedTraceId = "1234567890abcdef1234567890abcdef";
        AtomicReference<String> executionThreadName = new AtomicReference<>();
        AtomicReference<String> executionTraceId = new AtomicReference<>();
        WalletDepositConfirmedEventConsumer walletConfirmedConsumer = mock(WalletDepositConfirmedEventConsumer.class);
        doAnswer(invocation -> {
            executionThreadName.set(Thread.currentThread().getName());
            executionTraceId.set(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
            return null;
        }).when(walletConfirmedConsumer).consume(any(String.class), any(WalletDepositConfirmedEventPayload.class));
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                mock(MarketKlineUpdateEventConsumer.class),
                mock(MarketPriceTickEventConsumer.class),
                new TradingManagedKafkaExecutionSupport(executorService),
                walletConfirmedConsumer,
                mock(WalletDepositReversedEventConsumer.class),
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );

        try {
            listener.onWalletDepositConfirmed(
                    objectMapper.writeValueAsString(payload),
                    "evt-50002",
                    expectedTraceId
            );
        } finally {
            executorService.shutdown();
            Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertNotNull(executionThreadName.get());
        Assertions.assertTrue(executionThreadName.get().startsWith("trading-kafka-low-frequency-"));
        Assertions.assertNotEquals(listenerThreadName, executionThreadName.get());
        Assertions.assertEquals(expectedTraceId, executionTraceId.get());
        Assertions.assertNull(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
        verify(walletConfirmedConsumer).consume(eq("evt-50002"), eq(payload));
    }

    @Test
    void shouldIgnoreUnknownFieldsWhenDelegatingWalletDepositConfirmed() throws Exception {
        WalletDepositConfirmedEventConsumer walletConfirmedConsumer = mock(WalletDepositConfirmedEventConsumer.class);
        TradingManagedKafkaExecutionSupport executionSupport = mock(TradingManagedKafkaExecutionSupport.class);
        doAnswer(invocation -> {
            TradingManagedKafkaExecutionSupport.ThrowingRunnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(executionSupport).execute(any(String.class), any(TradingManagedKafkaExecutionSupport.ThrowingRunnable.class));
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                mock(MarketKlineUpdateEventConsumer.class),
                mock(MarketPriceTickEventConsumer.class),
                executionSupport,
                walletConfirmedConsumer,
                mock(WalletDepositReversedEventConsumer.class),
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );
        WalletDepositConfirmedEventPayload payload = new WalletDepositConfirmedEventPayload(
                77011L,
                9011L,
                ChainType.ETH,
                "USDT",
                "0xhash-extra",
                "0xfrom",
                "0xto",
                new BigDecimal("19.5"),
                12,
                12,
                OffsetDateTime.parse("2026-04-20T12:00:00Z")
        );

        listener.onWalletDepositConfirmed(
                """
                {"walletTxId":77011,"userId":9011,"chain":"ETH","token":"USDT","txHash":"0xhash-extra","fromAddress":"0xfrom","toAddress":"0xto","amount":"19.5","confirmations":12,"requiredConfirmations":12,"confirmedAt":"2026-04-20T12:00:00Z","newField":"ignored"}
                """,
                "evt-50002-extra",
                "1234567890abcdef1234567890abcdef"
        );

        verify(walletConfirmedConsumer).consume(eq("evt-50002-extra"), eq(payload));
    }

    @Test
    void shouldDelegateWalletDepositReversed() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("trading-kafka-low-frequency-", 0)
                .factory());
        WalletDepositReversedEventPayload payload = new WalletDepositReversedEventPayload(
                77002L,
                9002L,
                ChainType.TRON,
                "USDT",
                "0xreversed",
                "from",
                "to",
                new BigDecimal("21"),
                2,
                19,
                OffsetDateTime.parse("2026-04-17T12:00:00Z")
        );
        String listenerThreadName = Thread.currentThread().getName();
        String expectedTraceId = "1234567890abcdef1234567890abcdef";
        AtomicReference<String> executionThreadName = new AtomicReference<>();
        AtomicReference<String> executionTraceId = new AtomicReference<>();
        WalletDepositReversedEventConsumer walletReversedConsumer = mock(WalletDepositReversedEventConsumer.class);
        doAnswer(invocation -> {
            executionThreadName.set(Thread.currentThread().getName());
            executionTraceId.set(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
            return null;
        }).when(walletReversedConsumer).consume(any(String.class), any(WalletDepositReversedEventPayload.class));
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                mock(MarketKlineUpdateEventConsumer.class),
                mock(MarketPriceTickEventConsumer.class),
                new TradingManagedKafkaExecutionSupport(executorService),
                mock(WalletDepositConfirmedEventConsumer.class),
                walletReversedConsumer,
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );

        try {
            listener.onWalletDepositReversed(
                    objectMapper.writeValueAsString(payload),
                    "evt-50003",
                    expectedTraceId
            );
        } finally {
            executorService.shutdown();
            Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertNotNull(executionThreadName.get());
        Assertions.assertTrue(executionThreadName.get().startsWith("trading-kafka-low-frequency-"));
        Assertions.assertNotEquals(listenerThreadName, executionThreadName.get());
        Assertions.assertEquals(expectedTraceId, executionTraceId.get());
        Assertions.assertNull(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
        verify(walletReversedConsumer).consume(eq("evt-50003"), eq(payload));
    }

    @Test
    void shouldIgnoreUnknownFieldsWhenDelegatingWalletDepositReversed() throws Exception {
        WalletDepositReversedEventConsumer walletReversedConsumer = mock(WalletDepositReversedEventConsumer.class);
        TradingManagedKafkaExecutionSupport executionSupport = mock(TradingManagedKafkaExecutionSupport.class);
        doAnswer(invocation -> {
            TradingManagedKafkaExecutionSupport.ThrowingRunnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(executionSupport).execute(any(String.class), any(TradingManagedKafkaExecutionSupport.ThrowingRunnable.class));
        TradingKafkaEventListener listener = new TradingKafkaEventListener(
                objectMapper,
                new TradingCoreServiceProperties(),
                mock(MarketKlineUpdateEventConsumer.class),
                mock(MarketPriceTickEventConsumer.class),
                executionSupport,
                mock(WalletDepositConfirmedEventConsumer.class),
                walletReversedConsumer,
                mock(KycReviewedEventConsumer.class),
                mock(WalletWithdrawBroadcastEventConsumer.class),
                mock(WalletWithdrawConfirmedEventConsumer.class),
                mock(WalletWithdrawFailedEventConsumer.class),
                mock(FxRateUpdateEventConsumer.class)
        );
        WalletDepositReversedEventPayload payload = new WalletDepositReversedEventPayload(
                77012L,
                9012L,
                ChainType.BSC,
                "USDT",
                "0xreversed-extra",
                "from",
                "to",
                new BigDecimal("21"),
                2,
                15,
                OffsetDateTime.parse("2026-04-20T12:05:00Z")
        );

        listener.onWalletDepositReversed(
                """
                {"walletTxId":77012,"userId":9012,"chain":"BSC","token":"USDT","txHash":"0xreversed-extra","fromAddress":"from","toAddress":"to","amount":"21","confirmations":2,"requiredConfirmations":15,"reversedAt":"2026-04-20T12:05:00Z","newField":"ignored"}
                """,
                "evt-50003-extra",
                "1234567890abcdef1234567890abcdef"
        );

        verify(walletReversedConsumer).consume(eq("evt-50003-extra"), eq(payload));
    }
}
