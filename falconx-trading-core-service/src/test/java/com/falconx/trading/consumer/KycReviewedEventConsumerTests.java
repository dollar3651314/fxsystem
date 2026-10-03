package com.falconx.trading.consumer;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.falconx.identity.contract.event.KycReviewedEventPayload;
import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.repository.TradingNotificationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * KycReviewedEventConsumer 单测：覆盖 TC-KYC-060~063。
 *
 * <p>不启动 embedded Kafka；通过 mock 验证：
 *
 * <ul>
 *   <li>APPROVED 写 INFO 级站内信</li>
 *   <li>REJECTED 写 WARN 级站内信 + 含 rejectReason</li>
 *   <li>重复事件按 relatedKey + relatedId 跳过</li>
 *   <li>payload 缺字段抛 IllegalArgumentException</li>
 * </ul>
 */
class KycReviewedEventConsumerTests {

    private static final OffsetDateTime REVIEW_AT = OffsetDateTime.of(2026, 5, 14, 10, 30, 0, 0, ZoneOffset.UTC);

    @Test
    void shouldCreateInfoNotificationOnApproved() {
        TradingNotificationApplicationService notificationService = mock(TradingNotificationApplicationService.class);
        TradingNotificationRepository repository = mock(TradingNotificationRepository.class);
        when(repository.existsByRelated("kyc.reviewed", 5000001L)).thenReturn(false);
        KycReviewedEventConsumer consumer = new KycReviewedEventConsumer(notificationService, repository);

        consumer.consume("kyc-reviewed-5000001",
                new KycReviewedEventPayload(5000001L, 2000001L, "APPROVED", 1, 1001L, REVIEW_AT, null));

        // STAGE-8-NOTIFICATION 重构后 consumer 改调 send(templateCode, …) 而非旧 create()
        verify(notificationService).send(
                eq("KYC_APPROVED"),
                eq(2000001L),
                eq("KYC_APPROVED"),
                eq(Map.of()),
                eq("kyc.reviewed"),
                eq(5000001L),
                eq(null)
        );
    }

    @Test
    void shouldCreateWarnNotificationOnRejectedWithReason() {
        TradingNotificationApplicationService notificationService = mock(TradingNotificationApplicationService.class);
        TradingNotificationRepository repository = mock(TradingNotificationRepository.class);
        when(repository.existsByRelated("kyc.reviewed", 5000002L)).thenReturn(false);
        KycReviewedEventConsumer consumer = new KycReviewedEventConsumer(notificationService, repository);

        consumer.consume("kyc-reviewed-5000002",
                new KycReviewedEventPayload(5000002L, 2000001L, "REJECTED", 0, 1001L, REVIEW_AT, "证件模糊不清"));

        // STAGE-8-NOTIFICATION 重构后 consumer 改调 send(templateCode, …) + 透传 params
        verify(notificationService).send(
                eq("KYC_REJECTED"),
                eq(2000001L),
                eq("KYC_REJECTED"),
                eq(Map.of("rejectReason", "证件模糊不清")),
                eq("kyc.reviewed"),
                eq(5000002L),
                eq(null)
        );
    }

    @Test
    void shouldSkipDuplicateBasedOnRelatedKey() {
        TradingNotificationApplicationService notificationService = mock(TradingNotificationApplicationService.class);
        TradingNotificationRepository repository = mock(TradingNotificationRepository.class);
        when(repository.existsByRelated("kyc.reviewed", 5000003L)).thenReturn(true);
        KycReviewedEventConsumer consumer = new KycReviewedEventConsumer(notificationService, repository);

        consumer.consume("kyc-reviewed-5000003",
                new KycReviewedEventPayload(5000003L, 2000001L, "APPROVED", 1, 1001L, REVIEW_AT, null));

        verifyNoInteractions(notificationService);
    }

    @Test
    void shouldRejectPayloadWithMissingRequiredFields() {
        KycReviewedEventConsumer consumer = new KycReviewedEventConsumer(
                mock(TradingNotificationApplicationService.class),
                mock(TradingNotificationRepository.class)
        );

        Assertions.assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                "kyc-reviewed-bad",
                new KycReviewedEventPayload(5000004L, null, "APPROVED", 1, 1001L, REVIEW_AT, null)
        ));
    }

    @Test
    void shouldRejectUnknownResult() {
        TradingNotificationRepository repository = mock(TradingNotificationRepository.class);
        when(repository.existsByRelated("kyc.reviewed", 5000005L)).thenReturn(false);
        KycReviewedEventConsumer consumer = new KycReviewedEventConsumer(
                mock(TradingNotificationApplicationService.class),
                repository
        );

        Assertions.assertThrows(IllegalArgumentException.class, () -> consumer.consume(
                "kyc-reviewed-unknown",
                new KycReviewedEventPayload(5000005L, 2000001L, "PROBATION", 0, 1001L, REVIEW_AT, null)
        ));
    }
}
