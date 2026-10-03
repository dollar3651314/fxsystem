package com.falconx.trading.application;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.trading.entity.TradingWithdrawOrder;
import com.falconx.trading.repository.TradingWithdrawOrderRepository;
import com.falconx.trading.websocket.TradingAdminRealtimePushService;
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
 * STAGE-7-WITHDRAW Phase 2：大额延迟期推进调度器。
 *
 * <p>每 60s 扫一批 {@code status=APPROVED_DELAYED AND delayed_until <= now}，CAS 推进到
 * {@code APPROVED}。该状态会被 wallet 端识别为可广播状态（Phase 3 实施）。
 */
@Component
@ConditionalOnProperty(prefix = "falconx.trading.withdraw-delayed-scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WithdrawDelayedScheduler {

    private static final Logger log = LoggerFactory.getLogger(WithdrawDelayedScheduler.class);
    private static final int BATCH_LIMIT = 200;

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAdminRealtimePushService adminRealtimePushService;

    public WithdrawDelayedScheduler(TradingWithdrawOrderRepository withdrawOrderRepository,
                                     TradingAdminRealtimePushService adminRealtimePushService) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    @Scheduled(fixedDelayString = "${falconx.trading.withdraw-delayed-scheduler.interval-ms:60000}")
    public void run() {
        String traceId = TraceIdSupport.newTraceId();
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            advance();
        } catch (RuntimeException exception) {
            log.error("trading.withdraw.delayed.scheduler.failed message={}", exception.getMessage(), exception);
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    public int advance() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<TradingWithdrawOrder> due = withdrawOrderRepository.findExpiredDelayedOrders(now, BATCH_LIMIT);
        if (due.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (TradingWithdrawOrder order : due) {
            int updated = withdrawOrderRepository.markApprovedFromDelayedAtomic(order.id());
            if (updated > 0) {
                promoted++;
                adminRealtimePushService.publishWithdrawStatusChanged(
                        order.id(), order.userId(), "APPROVED", null, null, null);
                log.info("trading.withdraw.delayed.scheduler.promoted withdrawId={} userId={} delayedUntil={}",
                        order.id(), order.userId(), order.delayedUntil());
            }
        }
        if (promoted > 0) {
            log.info("trading.withdraw.delayed.scheduler.batch dueCount={} promoted={}", due.size(), promoted);
        }
        return promoted;
    }
}
