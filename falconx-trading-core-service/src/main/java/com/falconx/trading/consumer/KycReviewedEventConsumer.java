package com.falconx.trading.consumer;

import com.falconx.identity.contract.event.KycReviewedEventPayload;
import com.falconx.trading.application.TradingNotificationApplicationService;
import com.falconx.trading.repository.TradingNotificationRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * STAGE-6-KYC Phase 4：identity KYC 审核完成事件消费者。
 *
 * <p>消费 `falconx.identity.kyc.reviewed`，调用
 * {@link TradingNotificationApplicationService#create} 写 {@code t_notification}
 * 并经 WebSocket {@code notification.created} 推送给目标用户。
 *
 * <p>幂等：按 {@code relatedKey="kyc.reviewed"} + {@code relatedId=submissionId}
 * 在 {@link TradingNotificationRepository#existsByRelated} 查重；重复事件直接跳过。
 *
 * <p>失败语义：payload 缺字段直接抛出，由上层 listener 进入 DLQ。
 */
@Component
public class KycReviewedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KycReviewedEventConsumer.class);

    public static final String RELATED_KEY = "kyc.reviewed";

    private final TradingNotificationApplicationService notificationService;
    private final TradingNotificationRepository notificationRepository;

    public KycReviewedEventConsumer(TradingNotificationApplicationService notificationService,
                                     TradingNotificationRepository notificationRepository) {
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
    }

    public void consume(String eventId, KycReviewedEventPayload payload) {
        if (payload == null || payload.submissionId() == null || payload.userId() == null || payload.result() == null) {
            throw new IllegalArgumentException("kyc.reviewed payload missing required fields: eventId=" + eventId);
        }
        if (notificationRepository.existsByRelated(RELATED_KEY, payload.submissionId())) {
            log.info("trading.consumer.kyc.reviewed.skipped-duplicate eventId={} submissionId={} userId={}",
                    eventId, payload.submissionId(), payload.userId());
            return;
        }

        String templateCode;
        Map<String, String> params;
        switch (payload.result()) {
            case "APPROVED" -> {
                templateCode = "KYC_APPROVED";
                params = Map.of();
            }
            case "REJECTED" -> {
                templateCode = "KYC_REJECTED";
                String reason = payload.rejectReason() == null || payload.rejectReason().isBlank()
                        ? "审核未通过，请重新提交。"
                        : payload.rejectReason();
                params = Map.of("rejectReason", reason);
            }
            default -> throw new IllegalArgumentException(
                    "kyc.reviewed payload has unknown result: " + payload.result() + " eventId=" + eventId);
        }

        notificationService.send(
                templateCode,
                payload.userId(),
                templateCode,
                params,
                RELATED_KEY,
                payload.submissionId(),
                null
        );
        log.info("trading.consumer.kyc.reviewed.processed eventId={} submissionId={} userId={} result={}",
                eventId, payload.submissionId(), payload.userId(), payload.result());
    }
}
