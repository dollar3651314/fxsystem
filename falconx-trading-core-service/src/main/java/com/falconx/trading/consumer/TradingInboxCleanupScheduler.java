package com.falconx.trading.consumer;

import com.falconx.trading.repository.TradingInboxRepository;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2026-05-26: trading-core inbox done 行定时清理（性能分析报告 §0.5 Sprint 1 Phase B1）。
 *
 * <p>运行时实测 {@code t_inbox} 在 demo 服务器 188 万行 / 724MB 数据 + 271MB 索引，
 * 拖累 InnoDB buffer pool，并与 10597 次 row lock waits 高度相关。
 *
 * <p>策略与 {@code TradingOutboxCleanupScheduler} 同款：每小时跑一次，删 consumed_at 早于
 * 3 天前的 done 行；单次 LIMIT 5000 防长事务，循环直到当批返回 0；最多 100 批/次保护 IO。
 *
 * <p>幂等性：依赖 INSERT IGNORE + event_id UNIQUE 约束。即使删除后历史事件被重放，
 * INSERT IGNORE 会跳过；消费侧已通过状态机判断是否重复处理，与 inbox 行是否存在无关。
 */
@Component
public class TradingInboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingInboxCleanupScheduler.class);
    private static final int RETAIN_DAYS = 3;
    private static final int BATCH_LIMIT = 5000;
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final TradingInboxRepository tradingInboxRepository;

    public TradingInboxCleanupScheduler(TradingInboxRepository tradingInboxRepository) {
        this.tradingInboxRepository = tradingInboxRepository;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void cleanupProcessedInbox() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETAIN_DAYS);
        int totalDeleted = 0;
        for (int batchIndex = 0; batchIndex < MAX_BATCHES_PER_RUN; batchIndex++) {
            int deleted = tradingInboxRepository.deleteProcessedBefore(cutoff, BATCH_LIMIT);
            if (deleted <= 0) break;
            totalDeleted += deleted;
        }
        if (totalDeleted > 0) {
            log.info("trading.inbox.cleanup.completed totalDeleted={} cutoff={} retainDays={}",
                    totalDeleted, cutoff, RETAIN_DAYS);
        }
    }
}
