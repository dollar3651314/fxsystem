package com.falconx.market.producer;

import com.falconx.infrastructure.outbox.OutboxRetryDelayPolicy;
import com.falconx.market.entity.MarketOutboxMessage;
import com.falconx.market.repository.MarketOutboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * market-service Outbox 调度器。
 *
 * <p>该组件按固定频率轮询 `t_outbox`，
 * 负责把低频市场事件在事务提交后投递到 Kafka。
 */
@Component
public class MarketOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MarketOutboxDispatcher.class);
    private static final int MAX_RETRY_COUNT = 10;
    private static final int DEFAULT_BATCH_SIZE = 200;

    private final MarketOutboxRepository marketOutboxRepository;
    private final MarketOutboxEventPublisher marketOutboxEventPublisher;

    public MarketOutboxDispatcher(MarketOutboxRepository marketOutboxRepository,
                                  MarketOutboxEventPublisher marketOutboxEventPublisher) {
        this.marketOutboxRepository = marketOutboxRepository;
        this.marketOutboxEventPublisher = marketOutboxEventPublisher;
    }

    /**
     * 定时调度低频市场事件。
     */
    @Scheduled(initialDelay = 1000L, fixedDelay = 1000L)
    public void scheduledDispatchPendingMessages() {
        int dispatchedCount = dispatchPendingMessages();
        if (dispatchedCount > 0) {
            log.info("market.outbox.dispatch.batch.completed count={}", dispatchedCount);
        }
    }

    /**
     * 发送当前可调度的低频事件。
     *
     * @return 本次成功发送条数
     */
    public int dispatchPendingMessages() {
        OffsetDateTime now = OffsetDateTime.now();
        List<MarketOutboxMessage> pendingMessages = marketOutboxRepository.claimDispatchableBatch(now, DEFAULT_BATCH_SIZE);
        int successCount = 0;
        for (MarketOutboxMessage pendingMessage : pendingMessages) {
            try {
                marketOutboxEventPublisher.publish(pendingMessage);
                marketOutboxRepository.markSent(pendingMessage.outboxId(), OffsetDateTime.now());
                successCount++;
            } catch (RuntimeException exception) {
                OffsetDateTime nextRetryAt = OffsetDateTime.now()
                        .plusSeconds(OutboxRetryDelayPolicy.resolveDelaySeconds(pendingMessage.retryCount() + 1));
                marketOutboxRepository.markFailed(
                        pendingMessage.outboxId(),
                        nextRetryAt,
                        exception.getMessage(),
                        MAX_RETRY_COUNT
                );
                log.error("market.outbox.dispatch.failed eventId={} eventType={} retryCount={} nextRetryAt={}",
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
