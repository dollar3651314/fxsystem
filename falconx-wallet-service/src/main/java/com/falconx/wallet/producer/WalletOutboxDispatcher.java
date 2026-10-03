package com.falconx.wallet.producer;

import com.falconx.infrastructure.outbox.OutboxRetryDelayPolicy;
import com.falconx.wallet.entity.WalletOutboxMessage;
import com.falconx.wallet.repository.WalletOutboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * wallet-service Outbox 调度器。
 *
 * <p>该组件按固定频率轮询 `t_outbox`，
 * 在事务提交后把已认领的低频关键事件真正投递到 Kafka。
 */
@Component
public class WalletOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WalletOutboxDispatcher.class);
    private static final int MAX_RETRY_COUNT = 10;
    private static final int DEFAULT_BATCH_SIZE = 200;

    private final WalletOutboxRepository walletOutboxRepository;
    private final WalletOutboxEventPublisher walletOutboxEventPublisher;

    public WalletOutboxDispatcher(WalletOutboxRepository walletOutboxRepository,
                                  WalletOutboxEventPublisher walletOutboxEventPublisher) {
        this.walletOutboxRepository = walletOutboxRepository;
        this.walletOutboxEventPublisher = walletOutboxEventPublisher;
    }

    /**
     * 定时调度钱包 Outbox。
     */
    @Scheduled(initialDelay = 1000L, fixedDelay = 1000L)
    public void scheduledDispatchPendingMessages() {
        int dispatchedCount = dispatchPendingMessages();
        if (dispatchedCount > 0) {
            log.info("wallet.outbox.dispatch.batch.completed count={}", dispatchedCount);
        }
    }

    /**
     * 发送当前待发送的 Outbox 事件。
     *
     * @return 本次成功发送条数
     */
    public int dispatchPendingMessages() {
        OffsetDateTime now = OffsetDateTime.now();
        List<WalletOutboxMessage> pendingMessages = walletOutboxRepository.claimDispatchableBatch(now, DEFAULT_BATCH_SIZE);
        int successCount = 0;
        for (WalletOutboxMessage pendingMessage : pendingMessages) {
            try {
                walletOutboxEventPublisher.publish(pendingMessage);
                walletOutboxRepository.markSent(pendingMessage.outboxId(), OffsetDateTime.now());
                successCount++;
            } catch (RuntimeException exception) {
                OffsetDateTime nextRetryAt = OffsetDateTime.now()
                        .plusSeconds(OutboxRetryDelayPolicy.resolveDelaySeconds(pendingMessage.retryCount() + 1));
                walletOutboxRepository.markFailed(
                        pendingMessage.outboxId(),
                        nextRetryAt,
                        exception.getMessage(),
                        MAX_RETRY_COUNT
                );
                log.error("wallet.outbox.dispatch.failed eventId={} eventType={} retryCount={} nextRetryAt={}",
                        pendingMessage.eventId(),
                        pendingMessage.eventType(),
                        pendingMessage.retryCount() + 1,
                        nextRetryAt,
                        exception);
            }
        }
        return successCount;
    }
}
