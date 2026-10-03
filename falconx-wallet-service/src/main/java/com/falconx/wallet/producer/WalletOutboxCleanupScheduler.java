package com.falconx.wallet.producer;

import com.falconx.wallet.repository.WalletOutboxRepository;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2026-05-21: wallet-service outbox sent 行定时清理。
 *
 * <p>策略与 market-service / trading-core-service 同款：每小时跑一次，删 sent_at 早于
 * 3 天前的记录；单次 LIMIT 5000 防长事务，循环直到当批返回 0。
 */
@Component
public class WalletOutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(WalletOutboxCleanupScheduler.class);
    private static final int RETAIN_DAYS = 3;
    private static final int BATCH_LIMIT = 5000;
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final WalletOutboxRepository walletOutboxRepository;

    public WalletOutboxCleanupScheduler(WalletOutboxRepository walletOutboxRepository) {
        this.walletOutboxRepository = walletOutboxRepository;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void cleanupSentOutbox() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETAIN_DAYS);
        int totalDeleted = 0;
        for (int batchIndex = 0; batchIndex < MAX_BATCHES_PER_RUN; batchIndex++) {
            int deleted = walletOutboxRepository.deleteSentBefore(cutoff, BATCH_LIMIT);
            if (deleted <= 0) break;
            totalDeleted += deleted;
        }
        if (totalDeleted > 0) {
            log.info("wallet.outbox.cleanup.completed totalDeleted={} cutoff={} retainDays={}",
                    totalDeleted, cutoff, RETAIN_DAYS);
        }
    }
}
