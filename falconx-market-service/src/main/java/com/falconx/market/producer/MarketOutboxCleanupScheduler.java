package com.falconx.market.producer;

import com.falconx.market.repository.MarketOutboxRepository;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 2026-05-21 OOM 排查后续：定时清理已发送的 outbox 记录。
 *
 * <p>背景：t_outbox 表 status=2 (SENT) 行从未删除，已累积 68 万行 / 295 MB。
 * dispatcher 每秒查 t_outbox 找待发消息，表越大 InnoDB buffer 命中率越低，
 * 间接拖慢市场服务整体响应。
 *
 * <p>策略：每小时跑一次，删 sent_at 早于 3 天前的记录；单次 LIMIT 5000 防长事务，
 * 循环直到当批返回 0 表示删完为止。3 天保留窗口足够事故复盘 + 跨日审计。
 *
 * <p>幂等：删除条件是 status=2 AND sent_at < cutoff；正常 dispatcher 写入 status=2
 * 后立刻 sent_at 也设值。重复跑只会越删越少。
 */
@Component
public class MarketOutboxCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketOutboxCleanupScheduler.class);
    /** 保留 3 天已发送记录，足够事故复盘 + 审计跨日比对。 */
    private static final int RETAIN_DAYS = 3;
    /** 单次 DELETE LIMIT，避免长事务锁 t_outbox。 */
    private static final int BATCH_LIMIT = 5000;
    /** 单次调度循环最多删 50 万行（兜底防 runaway），正常情况几个 batch 就停。 */
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final MarketOutboxRepository marketOutboxRepository;

    public MarketOutboxCleanupScheduler(MarketOutboxRepository marketOutboxRepository) {
        this.marketOutboxRepository = marketOutboxRepository;
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public void cleanupSentOutbox() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(RETAIN_DAYS);
        int totalDeleted = 0;
        for (int batchIndex = 0; batchIndex < MAX_BATCHES_PER_RUN; batchIndex++) {
            int deleted = marketOutboxRepository.deleteSentBefore(cutoff, BATCH_LIMIT);
            if (deleted <= 0) break;
            totalDeleted += deleted;
        }
        if (totalDeleted > 0) {
            log.info("market.outbox.cleanup.completed totalDeleted={} cutoff={} retainDays={}",
                    totalDeleted, cutoff, RETAIN_DAYS);
        }
    }
}
