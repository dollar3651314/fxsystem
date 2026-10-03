package com.falconx.identity.consumer;

import com.falconx.identity.application.IdentityActivationApplicationService;
import com.falconx.identity.repository.IdentityInboxRepository;
import com.falconx.trading.contract.event.DepositCreditedEventPayload;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * `falconx.trading.deposit.credited` 事件消费骨架。
 *
 * <p>该类保持“事件适配完成后的领域消费入口”职责，
 * Kafka 注解和字符串反序列化统一放在独立 listener 适配器中：
 *
 * <pre>
 * Kafka Listener Adapter -> Consumer -> IdentityActivationApplicationService
 * </pre>
 */
@Component
public class DepositCreditedEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DepositCreditedEventConsumer.class);

    private final IdentityActivationApplicationService identityActivationApplicationService;
    private final IdentityInboxRepository identityInboxRepository;

    public DepositCreditedEventConsumer(IdentityActivationApplicationService identityActivationApplicationService,
                                        IdentityInboxRepository identityInboxRepository) {
        this.identityActivationApplicationService = identityActivationApplicationService;
        this.identityInboxRepository = identityInboxRepository;
    }

    /**
     * 处理入账完成事件。
     *
     * @param eventId 事件唯一 ID
     * @param payload 事件 payload
     */
    @Transactional
    public void handle(String eventId, DepositCreditedEventPayload payload) {
        if (identityInboxRepository.existsProcessed(eventId)) {
            log.info("identity.consumer.deposit.credited.duplicate eventId={} userId={}", eventId, payload.userId());
            return;
        }
        identityActivationApplicationService.activateUserFromDepositCredited(eventId, payload);
        identityInboxRepository.saveProcessed(
                eventId,
                "trading.deposit.credited",
                "falconx-trading-core-service",
                payload,
                OffsetDateTime.now()
        );
    }
}
