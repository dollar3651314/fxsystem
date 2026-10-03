package com.falconx.trading.producer;

import com.falconx.infrastructure.outbox.OutboxRetryDelayPolicy;
import com.falconx.trading.entity.TradingOutboxMessage;
import com.falconx.trading.repository.TradingOutboxRepository;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 交易核心 Outbox 调度器骨架。
 *
 * <p>该组件用于体现 Stage 3B 之后对 Outbox 模式的遵守：
 * 当前已经具备“认领批次 -> Kafka 发送 -> 成功回写 / 失败退避”的基础语义。
 */
@Component
public class TradingOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TradingOutboxDispatcher.class);
    private static final int MAX_RETRY_COUNT = 10;
    private static final int DEFAULT_BATCH_SIZE = 200;

    private final TradingOutboxRepository tradingOutboxRepository;
    private final TradingOutboxEventPublisher tradingOutboxEventPublisher;

    public TradingOutboxDispatcher(TradingOutboxRepository tradingOutboxRepository,
                                   TradingOutboxEventPublisher tradingOutboxEventPublisher) {
        this.tradingOutboxRepository = tradingOutboxRepository;
        this.tradingOutboxEventPublisher = tradingOutboxEventPublisher;
    }

    /**
     * 按固定频率调度交易核心 Outbox。
     */
    @Scheduled(initialDelay = 1000L, fixedDelay = 1000L)
    public void scheduledDispatchPendingMessages() {
        int dispatchedCount = dispatchPendingMessages();
        if (dispatchedCount > 0) {
            log.info("trading.outbox.dispatch.batch.completed count={}", dispatchedCount);
        }
    }

    /**
     * 发送全部待发送 Outbox 事件。
     *
     * @return 本次发送事件条数
     */
    public int dispatchPendingMessages() {
        OffsetDateTime now = OffsetDateTime.now();
        List<TradingOutboxMessage> pendingMessages = tradingOutboxRepository.claimDispatchableBatch(now, DEFAULT_BATCH_SIZE);
        int successCount = 0;
        for (TradingOutboxMessage pendingMessage : pendingMessages) {
            try {
                tradingOutboxEventPublisher.publish(pendingMessage);
                tradingOutboxRepository.markSent(pendingMessage.outboxId(), OffsetDateTime.now());
                successCount++;
            } catch (RuntimeException exception) {
                OffsetDateTime nextRetryAt = OffsetDateTime.now().plusSeconds(OutboxRetryDelayPolicy.resolveDelaySeconds(
                        pendingMessage.retryCount() + 1
                ));
                tradingOutboxRepository.markFailed(
                        pendingMessage.outboxId(),
                        nextRetryAt,
                        exception.getMessage(),
                        MAX_RETRY_COUNT
                );
                log.error("trading.outbox.dispatch.failed eventId={} eventType={} retryCount={} nextRetryAt={}",
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
