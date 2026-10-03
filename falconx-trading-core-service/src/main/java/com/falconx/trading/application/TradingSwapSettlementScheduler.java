package com.falconx.trading.application;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.trading.config.TradingCoreServiceProperties;
import com.falconx.trading.service.TradingSwapSettlementService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 隔夜利息结算调度器。
 *
 * <p>该调度器只负责生成定时任务 traceId 并触发批量扫描，
 * 具体 owner 账务逻辑由 `TradingSwapSettlementService` 承担。
 */
@Component
@ConditionalOnProperty(prefix = "falconx.trading.swap", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TradingSwapSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradingSwapSettlementScheduler.class);

    private final TradingSwapSettlementService tradingSwapSettlementService;
    private final TradingCoreServiceProperties properties;

    public TradingSwapSettlementScheduler(TradingSwapSettlementService tradingSwapSettlementService,
                                          TradingCoreServiceProperties properties) {
        this.tradingSwapSettlementService = tradingSwapSettlementService;
        this.properties = properties;
    }

    /**
     * 按固定频率触发 `Swap` 结算扫描。
     */
    @Scheduled(
            cron = "${falconx.trading.swap.settlement-cron:0 * * * * *}",
            zone = "${falconx.trading.swap.settlement-zone:UTC}"
    )
    public void settleDuePositionsOnSchedule() {
        String traceId = TraceIdSupport.newTraceId();
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            log.info("trading.swap.settlement.batch.start cron={} zone={}",
                    properties.getSwap().getSettlementCron(),
                    properties.getSwap().getSettlementZone());
            tradingSwapSettlementService.settleDuePositions(OffsetDateTime.now(ZoneOffset.UTC));
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }
}
