package com.falconx.identity.consumer;

import tools.jackson.databind.json.JsonMapper;
import com.falconx.identity.config.IdentityServiceProperties;
import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.trading.contract.event.DepositCreditedEventPayload;
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

/**
 * `IdentityKafkaEventListener` 单元测试。
 *
 * <p>该测试确认 identity-service 已经具备 Kafka 事件适配入口。
 */
class IdentityKafkaEventListenerTests {

    @Test
    void shouldDeserializeKafkaPayloadAndDelegateToDomainConsumer() throws Exception {
        ExecutorService executorService = Executors.newSingleThreadExecutor(Thread.ofPlatform()
                .name("identity-kafka-", 0)
                .factory());
        DepositCreditedEventPayload payload = new DepositCreditedEventPayload(
                1001L,
                2002L,
                3003L,
                "ETH",
                "USDT",
                "0xhash",
                new BigDecimal("99.99"),
                OffsetDateTime.parse("2026-04-17T12:00:00Z")
        );
        String listenerThreadName = Thread.currentThread().getName();
        String expectedTraceId = "1234567890abcdef1234567890abcdef";
        AtomicReference<String> executionThreadName = new AtomicReference<>();
        AtomicReference<String> executionTraceId = new AtomicReference<>();
        DepositCreditedEventConsumer consumer = mock(DepositCreditedEventConsumer.class);
        doAnswer(invocation -> {
            executionThreadName.set(Thread.currentThread().getName());
            executionTraceId.set(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
            return null;
        }).when(consumer).handle(any(String.class), any(DepositCreditedEventPayload.class));
        IdentityKafkaEventListener listener = new IdentityKafkaEventListener(
                JsonMapper.builder().build(),
                new IdentityServiceProperties(),
                consumer,
                new IdentityManagedKafkaExecutionSupport(executorService)
        );

        try {
            listener.onDepositCredited(
                    JsonMapper.builder().build().writeValueAsString(payload),
                    "evt-40001",
                    expectedTraceId
            );
        } finally {
            executorService.shutdown();
            Assertions.assertTrue(executorService.awaitTermination(5, TimeUnit.SECONDS));
        }

        Assertions.assertNotNull(executionThreadName.get());
        Assertions.assertTrue(executionThreadName.get().startsWith("identity-kafka-"));
        Assertions.assertNotEquals(listenerThreadName, executionThreadName.get());
        Assertions.assertEquals(expectedTraceId, executionTraceId.get());
        Assertions.assertNull(MDC.get(TraceIdConstants.TRACE_ID_MDC_KEY));
        verify(consumer).handle(eq("evt-40001"), eq(payload));
    }

    @Test
    void shouldIgnoreUnknownFieldsWhenDeserializingDepositCreditedPayload() throws Exception {
        DepositCreditedEventConsumer consumer = mock(DepositCreditedEventConsumer.class);
        IdentityManagedKafkaExecutionSupport executionSupport = mock(IdentityManagedKafkaExecutionSupport.class);
        doAnswer(invocation -> {
            IdentityManagedKafkaExecutionSupport.ThrowingRunnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(executionSupport).execute(any(String.class), any(IdentityManagedKafkaExecutionSupport.ThrowingRunnable.class));
        IdentityKafkaEventListener listener = new IdentityKafkaEventListener(
                JsonMapper.builder().build(),
                new IdentityServiceProperties(),
                consumer,
                executionSupport
        );
        DepositCreditedEventPayload payload = new DepositCreditedEventPayload(
                1002L,
                2003L,
                3004L,
                "ETH",
                "USDT",
                "0xhash-extra",
                new BigDecimal("88.88"),
                OffsetDateTime.parse("2026-04-20T12:00:00Z")
        );

        listener.onDepositCredited(
                """
                {"depositId":1002,"userId":2003,"accountId":3004,"chain":"ETH","token":"USDT","txHash":"0xhash-extra","amount":"88.88","creditedAt":"2026-04-20T12:00:00Z","newField":"ignored"}
                """,
                "evt-40002",
                "1234567890abcdef1234567890abcdef"
        );

        verify(consumer).handle(eq("evt-40002"), eq(payload));
    }
}
