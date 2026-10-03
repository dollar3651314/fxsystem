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
 * STAGE-7-WITHDRAW Phase 2：出金冷静期推进调度器。
 *
 * <p>每 30s 扫一批 {@code status=COOLING AND cooling_until <= now}，CAS 推进到 {@code PENDING}。
 * 状态机其他迁移（PENDING→APPROVED / APPROVED_DELAYED 等）由 admin 审核或后续阶段调度器驱动，
 * 本调度器只处理 COOLING→PENDING 一条边。
 */
@Component
@ConditionalOnProperty(prefix = "falconx.trading.withdraw-cooling-scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WithdrawCoolingScheduler {

    private static final Logger log = LoggerFactory.getLogger(WithdrawCoolingScheduler.class);
    private static final int BATCH_LIMIT = 200;

    private final TradingWithdrawOrderRepository withdrawOrderRepository;
    private final TradingAdminRealtimePushService adminRealtimePushService;

    public WithdrawCoolingScheduler(TradingWithdrawOrderRepository withdrawOrderRepository,
                                     TradingAdminRealtimePushService adminRealtimePushService) {
        this.withdrawOrderRepository = withdrawOrderRepository;
        this.adminRealtimePushService = adminRealtimePushService;
    }

    @Scheduled(fixedDelayString = "${falconx.trading.withdraw-cooling-scheduler.interval-ms:30000}")
    public void run() {
        String traceId = TraceIdSupport.newTraceId();
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            advance();
        } catch (RuntimeException exception) {
            log.error("trading.withdraw.cooling.scheduler.failed message={}", exception.getMessage(), exception);
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    public int advance() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<TradingWithdrawOrder> due = withdrawOrderRepository.findExpiredCoolingOrders(now, BATCH_LIMIT);
        if (due.isEmpty()) {
            return 0;
        }
        int promoted = 0;
        for (TradingWithdrawOrder order : due) {
            int updated = withdrawOrderRepository.markPendingFromCoolingAtomic(order.id());
            if (updated > 0) {
                promoted++;
                adminRealtimePushService.publishWithdrawStatusChanged(
                        order.id(), order.userId(), "PENDING", null, null, null);
                log.info("trading.withdraw.cooling.scheduler.promoted withdrawId={} userId={} coolingUntil={}",
                        order.id(), order.userId(), order.coolingUntil());
            }
        }
        if (promoted > 0) {
            log.info("trading.withdraw.cooling.scheduler.batch dueCount={} promoted={}", due.size(), promoted);
        }
        return promoted;
    }
}
