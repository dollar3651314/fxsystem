package com.falconx.identity.consumer;

import com.falconx.identity.repository.IdentityInboxRepository;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2026-05-26: identity inbox done 行定时清理（性能分析报告 §0.5 Sprint 1 Phase B1）。
 *
 * <p>identity inbox 用于 {@code falconx.trading.deposit.credited} 事件去重。事件量与
 * 入金笔数挂钩，量级远低于 trading inbox（trading 还要消费 market.price.tick 等高频事件），
 * 但同款 cleanup 模式保留以防长期累积。
 *
 * <p>策略与 trading/market/wallet outbox cleanup 同款：每小时跑一次，删 consumed_at 早于
 * 3 天前的 done 行；单次 LIMIT 5000，最多 100 批/次。
 */
@Component
public class IdentityInboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdentityInboxCleanupScheduler.class);
    private static final int RETAIN_DAYS = 3;
    private static final int BATCH_LIMIT = 5000;
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final IdentityInboxRepository identityInboxRepository;

    public IdentityInboxCleanupScheduler(IdentityInboxRepository identityInboxRepository) {
        this.identityInboxRepository = identityInboxRepository;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void cleanupProcessedInbox() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETAIN_DAYS);
        int totalDeleted = 0;
        for (int batchIndex = 0; batchIndex < MAX_BATCHES_PER_RUN; batchIndex++) {
            int deleted = identityInboxRepository.deleteProcessedBefore(cutoff, BATCH_LIMIT);
            if (deleted <= 0) break;
            totalDeleted += deleted;
        }
        if (totalDeleted > 0) {
            log.info("identity.inbox.cleanup.completed totalDeleted={} cutoff={} retainDays={}",
                    totalDeleted, cutoff, RETAIN_DAYS);
        }
    }
}
