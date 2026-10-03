package com.falconx.trading.application;

import com.falconx.infrastructure.trace.TraceIdConstants;
import com.falconx.infrastructure.trace.TraceIdSupport;
import com.falconx.trading.entity.TradingRiskConfig;
import com.falconx.trading.entity.TradingRiskControlActionType;
import com.falconx.trading.repository.TradingRiskConfigRepository;
import com.falconx.trading.repository.TradingRiskControlActionRepository;
import com.falconx.trading.repository.TradingRiskExposureRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BBOOK-RISK-CONTROL-01：平台总净敞口守护调度器。
 *
 * <p>每 5 秒读 {@code t_risk_exposure} 全表，累计 |netExposureUsd|，超过
 * 平台行（{@code t_risk_config.symbol IS NULL}）的 {@code hedge_threshold_usd}
 * 阈值时激活 {@code GLOBAL_PAUSE}（symbol=NULL）；恢复时停用。
 */
@Component
@ConditionalOnProperty(prefix = "falconx.trading.platform-exposure-guard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlatformExposureGuardScheduler {

    private static final Logger log = LoggerFactory.getLogger(PlatformExposureGuardScheduler.class);
    private static final String TRIGGER_SOURCE_AUTO_PLATFORM = "AUTO_PLATFORM_EXPOSURE";

    private final TradingRiskExposureRepository exposureRepository;
    private final TradingRiskConfigRepository riskConfigRepository;
    private final TradingRiskControlActionRepository actionRepository;
    private final com.falconx.trading.websocket.TradingAdminRealtimePushService adminPushService;

    public PlatformExposureGuardScheduler(TradingRiskExposureRepository exposureRepository,
                                          TradingRiskConfigRepository riskConfigRepository,
                                          TradingRiskControlActionRepository actionRepository,
                                          com.falconx.trading.websocket.TradingAdminRealtimePushService adminPushService) {
        this.exposureRepository = exposureRepository;
        this.riskConfigRepository = riskConfigRepository;
        this.actionRepository = actionRepository;
        this.adminPushService = adminPushService;
    }

    @Scheduled(fixedDelayString = "${falconx.trading.platform-exposure-guard.interval-ms:5000}")
    public void run() {
        String traceId = TraceIdSupport.newTraceId();
        MDC.put(TraceIdConstants.TRACE_ID_MDC_KEY, traceId);
        try {
            evaluate();
        } catch (RuntimeException exception) {
            log.error("trading.risk.platform-exposure.guard.failed message={}", exception.getMessage(), exception);
        } finally {
            MDC.remove(TraceIdConstants.TRACE_ID_MDC_KEY);
        }
    }

    private void evaluate() {
        TradingRiskConfig platformRow = riskConfigRepository.findPlatformRow().orElse(null);
        if (platformRow == null) {
            return;
        }
        BigDecimal threshold = platformRow.hedgeThresholdUsd();
        if (threshold == null || threshold.signum() <= 0) {
            return;
        }
        BigDecimal totalUsd = exposureRepository.sumAbsNetExposureUsdAllSymbols();

        if (totalUsd.compareTo(threshold) >= 0) {
            boolean activated = actionRepository.activateIfAbsent(
                    null,
                    TradingRiskControlActionType.GLOBAL_PAUSE,
                    TRIGGER_SOURCE_AUTO_PLATFORM,
                    "PLATFORM_EXPOSURE_EXCEEDED totalUsd=" + totalUsd + " threshold=" + threshold,
                    null
            );
            if (activated) {
                log.warn("trading.risk.platform-exposure.activated action=GLOBAL_PAUSE source=AUTO_PLATFORM_EXPOSURE totalUsd={} threshold={}",
                        totalUsd, threshold);
                pushAdminEvent(true, totalUsd, threshold);
            }
        } else {
            // 仅当之前存在激活记录时才推送 deactivated 事件
            boolean wasActive = actionRepository.hasActiveGlobalPause();
            actionRepository.deactivate(null, TradingRiskControlActionType.GLOBAL_PAUSE, TRIGGER_SOURCE_AUTO_PLATFORM);
            if (wasActive) {
                log.info("trading.risk.platform-exposure.deactivated action=GLOBAL_PAUSE source=AUTO_PLATFORM_EXPOSURE totalUsd={} threshold={}",
                        totalUsd, threshold);
                pushAdminEvent(false, totalUsd, threshold);
            }
        }
    }

    private void pushAdminEvent(boolean active, BigDecimal totalUsd, BigDecimal threshold) {
        try {
            com.falconx.trading.entity.TradingRiskControlAction synthetic = new com.falconx.trading.entity.TradingRiskControlAction(
                    null, null, TradingRiskControlActionType.GLOBAL_PAUSE, active,
                    TRIGGER_SOURCE_AUTO_PLATFORM,
                    "PLATFORM_EXPOSURE " + (active ? "EXCEEDED" : "RECOVERED")
                            + " totalUsd=" + totalUsd + " threshold=" + threshold,
                    null, null, null
            );
            adminPushService.publishRiskActionChanged(synthetic, java.time.OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("trading.risk.platform-exposure.push.failed message={}", exception.getMessage());
        }
    }
}
