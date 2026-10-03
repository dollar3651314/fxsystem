package com.falconx.wallet.application;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.wallet.entity.WalletWithdrawWhitelist;
import com.falconx.wallet.repository.WalletWithdrawWhitelistRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * STAGE-7-WITHDRAW Phase 2：白名单 24h 冷静期推进调度器。
 *
 * <p>每 60s 扫一批 {@code status=PENDING AND created_at + 24h <= now}，CAS 推进到 ACTIVE
 * 并写 {@code activated_at}。冷静期长度由 {@link #COOLING_DURATION} 控制（与
 * trading-core {@code falconx.trading.withdraw.whitelist-cooling-duration} 业务约束一致；
 * 调度器层固定 24h，不依赖配置以避免双端不一致）。
 */
@Component
@ConditionalOnProperty(prefix = "falconx.wallet.withdraw-whitelist-cooling-scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WalletWithdrawWhitelistCoolingScheduler {

    private static final Logger log = LoggerFactory.getLogger(WalletWithdrawWhitelistCoolingScheduler.class);
    private static final Duration COOLING_DURATION = Duration.ofHours(24);
    private static final int BATCH_LIMIT = 200;

    private final WalletWithdrawWhitelistRepository whitelistRepository;

    public WalletWithdrawWhitelistCoolingScheduler(WalletWithdrawWhitelistRepository whitelistRepository) {
        this.whitelistRepository = whitelistRepository;
    }

    @Scheduled(fixedDelayString = "${falconx.wallet.withdraw-whitelist-cooling-scheduler.interval-ms:60000}")
    public void run() {
        String traceId = TraceIdSupport.newTraceId();
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            advance();
        } catch (RuntimeException exception) {
            log.error("wallet.withdraw.whitelist.cooling.scheduler.failed message={}",
                    exception.getMessage(), exception);
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    public int advance() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime threshold = now.minus(COOLING_DURATION);
        List<WalletWithdrawWhitelist> due = whitelistRepository.findExpiredPendingWhitelists(threshold, BATCH_LIMIT);
        if (due.isEmpty()) {
            return 0;
        }
        int activated = 0;
        for (WalletWithdrawWhitelist whitelist : due) {
            int updated = whitelistRepository.markActiveFromPendingAtomic(whitelist.id(), now);
            if (updated > 0) {
                activated++;
                log.info("wallet.withdraw.whitelist.cooling.scheduler.activated id={} userId={} createdAt={}",
                        whitelist.id(), whitelist.userId(), whitelist.createdAt());
            }
        }
        if (activated > 0) {
            log.info("wallet.withdraw.whitelist.cooling.scheduler.batch dueCount={} activated={}",
                    due.size(), activated);
        }
        return activated;
    }
}
