package com.falconx.trading.producer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.falconx.trading.event.TradingHedgeAlertEvent;
import com.falconx.trading.entity.TradingHedgeTriggerSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(OutputCaptureExtension.class)
class SpringTradingHedgeAlertEventPublisherTests {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldPublishImmediatelyWhenNoTransactionSynchronizationExists(CapturedOutput output) {
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        SpringTradingHedgeAlertEventPublisher publisher = new SpringTradingHedgeAlertEventPublisher(applicationEventPublisher);
        TradingHedgeAlertEvent event = sampleEvent();

        publisher.publishAfterCommit(event);

        verify(applicationEventPublisher).publishEvent(event);
        Assertions.assertTrue(output.toString().contains("trading.risk.hedge.event.published"));
    }

    @Test
    void shouldDelayPublishUntilAfterCommitWhenSynchronizationIsActive(CapturedOutput output) {
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        SpringTradingHedgeAlertEventPublisher publisher = new SpringTradingHedgeAlertEventPublisher(applicationEventPublisher);
        TradingHedgeAlertEvent event = sampleEvent();
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event);

        verify(applicationEventPublisher, never()).publishEvent(event);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(applicationEventPublisher).publishEvent(event);
        Assertions.assertTrue(output.toString().contains("trading.risk.hedge.event.published"));
    }

    @Test
    void shouldSwallowListenerFailureWhenPublishingImmediately() {
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        SpringTradingHedgeAlertEventPublisher publisher = new SpringTradingHedgeAlertEventPublisher(applicationEventPublisher);
        TradingHedgeAlertEvent event = sampleEvent();
        doThrow(new IllegalStateException("listener boom")).when(applicationEventPublisher).publishEvent(event);

        Assertions.assertDoesNotThrow(() -> publisher.publishAfterCommit(event));
        verify(applicationEventPublisher).publishEvent(event);
    }

    @Test
    void shouldSwallowListenerFailureWhenPublishingAfterCommit() {
        ApplicationEventPublisher applicationEventPublisher = mock(ApplicationEventPublisher.class);
        SpringTradingHedgeAlertEventPublisher publisher = new SpringTradingHedgeAlertEventPublisher(applicationEventPublisher);
        TradingHedgeAlertEvent event = sampleEvent();
        doThrow(new IllegalStateException("listener boom")).when(applicationEventPublisher).publishEvent(event);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event);

        Assertions.assertDoesNotThrow(() -> {
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        });
        verify(applicationEventPublisher).publishEvent(event);
    }

    private TradingHedgeAlertEvent sampleEvent() {
        return new TradingHedgeAlertEvent(
                OffsetDateTime.now(),
                "BTCUSDT",
                new BigDecimal("19990.00000000"),
                new BigDecimal("15000.00000000"),
                1001L,
                TradingHedgeTriggerSource.OPEN_POSITION,
                new BigDecimal("9995.00000000"),
                OffsetDateTime.now(),
                "spring-event-unit-test",
                99L
        );
    }
}
