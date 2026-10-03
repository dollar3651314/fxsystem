package com.falconx.trading.application;

import com.falconx.infrastructure.id.IdGenerator;
import com.falconx.trading.engine.PriceAlertEvaluator;
import com.falconx.trading.engine.SymbolTriggerActivityRegistry;
import com.falconx.trading.entity.TradingPriceAlert;
import com.falconx.trading.entity.TradingPriceAlertDirection;
import com.falconx.trading.entity.TradingPriceAlertStatus;
import com.falconx.trading.entity.TradingQuoteSnapshot;
import com.falconx.trading.error.TradingBusinessException;
import com.falconx.trading.error.TradingErrorCode;
import com.falconx.trading.repository.TradingPriceAlertRepository;
import com.falconx.trading.repository.TradingQuoteSnapshotRepository;
import com.falconx.trading.websocket.PriceAlertTriggeredPayload;
import com.falconx.trading.websocket.TradingUserRealtimePushService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAGE-4-PRICE-ALERT：价格告警应用服务。
 *
 * <p>负责：
 * <ul>
 *   <li>创建告警（10 条上限校验 + direction 自动推导）</li>
 *   <li>查询用户告警（按 status / symbol 过滤）</li>
 *   <li>撤销告警</li>
 *   <li>触发执行（被 PriceAlertEvaluator 在 QuoteDrivenEngine 内选中后调用）</li>
 * </ul>
 */
@Service
public class TradingPriceAlertApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TradingPriceAlertApplicationService.class);
    private static final int MAX_ACTIVE_ALERTS_PER_USER = 10;
    private static final int MAX_TRIGGER_COUNT = 3;

    private final TradingPriceAlertRepository priceAlertRepository;
    private final TradingQuoteSnapshotRepository quoteSnapshotRepository;
    private final TradingUserRealtimePushService realtimePushService;
    private final PriceAlertEvaluator priceAlertEvaluator;
    private final IdGenerator idGenerator;
    private final SymbolTriggerActivityRegistry triggerActivityRegistry;
    // STAGE-8-NOTIFICATION：触发时同步落站内信；可选注入避免循环依赖回归。
    private final TradingNotificationApplicationService notificationService;

    public TradingPriceAlertApplicationService(TradingPriceAlertRepository priceAlertRepository,
                                                TradingQuoteSnapshotRepository quoteSnapshotRepository,
                                                TradingUserRealtimePushService realtimePushService,
                                                PriceAlertEvaluator priceAlertEvaluator,
                                                IdGenerator idGenerator,
                                                SymbolTriggerActivityRegistry triggerActivityRegistry,
                                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                                TradingNotificationApplicationService notificationService) {
        this.priceAlertRepository = priceAlertRepository;
        this.quoteSnapshotRepository = quoteSnapshotRepository;
        this.realtimePushService = realtimePushService;
        this.priceAlertEvaluator = priceAlertEvaluator;
        this.idGenerator = idGenerator;
        this.triggerActivityRegistry = triggerActivityRegistry;
        this.notificationService = notificationService;
    }

    /**
     * 创建告警。direction 为 null 时按 targetPrice vs currentMark 自动推导。
     */
    @Transactional
    public TradingPriceAlert create(Long userId, String symbol, TradingPriceAlertDirection direction,
                                     BigDecimal targetPrice, String note) {
        // 1. 10 条上限校验
        int activeCount = priceAlertRepository.countActiveByUserId(userId);
        if (activeCount >= MAX_ACTIVE_ALERTS_PER_USER) {
            throw new TradingBusinessException(
                    TradingErrorCode.PRICE_ALERT_LIMIT_EXCEEDED,
                    Map.of("userId", userId, "activeCount", activeCount, "limit", MAX_ACTIVE_ALERTS_PER_USER));
        }

        // 2. 取 markPrice 作为 basePrice + direction 推导
        TradingQuoteSnapshot quote = quoteSnapshotRepository.findBySymbol(symbol).orElse(null);
        BigDecimal currentMark = quote == null ? null : quote.mark();

        TradingPriceAlertDirection effectiveDirection = direction;
        if (effectiveDirection == null) {
            if (currentMark == null) {
                throw new TradingBusinessException(
                        TradingErrorCode.PRICE_ALERT_INVALID_DIRECTION,
                        Map.of("reason", "current-mark-unavailable-and-direction-not-provided"));
            }
            int cmp = targetPrice.compareTo(currentMark);
            if (cmp == 0) {
                throw new TradingBusinessException(
                        TradingErrorCode.PRICE_ALERT_INVALID_DIRECTION,
                        Map.of("targetPrice", targetPrice, "markPrice", currentMark, "reason", "equal"));
            }
            effectiveDirection = cmp > 0 ? TradingPriceAlertDirection.ABOVE : TradingPriceAlertDirection.BELOW;
        } else if (currentMark != null) {
            // 用户显式传 direction 但与当前价矛盾（如 ABOVE 但 target < currentMark），警告但不阻断
            int cmp = targetPrice.compareTo(currentMark);
            boolean conflicting = (effectiveDirection == TradingPriceAlertDirection.ABOVE && cmp < 0)
                    || (effectiveDirection == TradingPriceAlertDirection.BELOW && cmp > 0);
            if (conflicting) {
                log.warn("trading.price-alert.create.direction-conflict userId={} symbol={} direction={} target={} mark={}",
                        userId, symbol, effectiveDirection, targetPrice, currentMark);
            }
        }

        // 3. 持久化
        long id = idGenerator.nextId();
        OffsetDateTime now = OffsetDateTime.now();
        TradingPriceAlert alert = new TradingPriceAlert(
                id, userId, symbol, effectiveDirection, targetPrice,
                TradingPriceAlertStatus.ACTIVE, note, currentMark,
                0, null, null, null, null,
                now, now);
        priceAlertRepository.insert(alert);
        triggerActivityRegistry.markPriceAlertActive(symbol);
        log.info("trading.price-alert.created id={} userId={} symbol={} direction={} target={} basePrice={}",
                id, userId, symbol, effectiveDirection, targetPrice, currentMark);
        return alert;
    }

    public List<TradingPriceAlert> list(Long userId, Integer statusCode, String symbol, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return priceAlertRepository.findByUserId(userId, statusCode, symbol, offset, pageSize);
    }

    public long count(Long userId, Integer statusCode, String symbol) {
        return priceAlertRepository.countByUserId(userId, statusCode, symbol);
    }

    @Transactional
    public TradingPriceAlert cancel(Long userId, Long alertId) {
        TradingPriceAlert alert = priceAlertRepository.findByIdForUpdate(alertId)
                .orElseThrow(() -> new TradingBusinessException(
                        TradingErrorCode.PRICE_ALERT_NOT_FOUND, Map.of("id", alertId)));
        if (!alert.userId().equals(userId)) {
            // 404 而非 403：避免泄漏其他用户告警存在性
            throw new TradingBusinessException(TradingErrorCode.PRICE_ALERT_NOT_FOUND, Map.of("id", alertId));
        }
        if (alert.status() != TradingPriceAlertStatus.ACTIVE) {
            // 已是终态：幂等返回当前快照
            return alert;
        }
        int updated = priceAlertRepository.markCancelled(alertId,
                TradingPriceAlertStatus.CANCELLED.code(), "USER");
        if (updated == 0) {
            throw new TradingBusinessException(TradingErrorCode.PRICE_ALERT_NOT_FOUND, Map.of("id", alertId));
        }
        if (priceAlertRepository.countActiveBySymbol(alert.symbol()) == 0) {
            triggerActivityRegistry.markPriceAlertInactive(alert.symbol());
        }
        log.info("trading.price-alert.cancelled id={} userId={}", alertId, userId);
        return priceAlertRepository.findById(alertId).orElseThrow();
    }

    /**
     * 触发执行（被 QuoteDrivenEngine 在 tick 内通过 PriceAlertEvaluator 选中后调用）。
     *
     * <p>SQL 层的 selectTriggerableBySymbol 已过滤 5min 节流；本方法用 trigger_count
     * CAS 写入保护并发竞态。返回 true 表示成功占有触发。
     */
    @Transactional
    public boolean trigger(Long alertId, BigDecimal markPrice) {
        TradingPriceAlert alert = priceAlertRepository.findByIdForUpdate(alertId).orElse(null);
        if (alert == null || alert.status() != TradingPriceAlertStatus.ACTIVE) {
            return false;
        }
        // 二次校验价格条件（FOR UPDATE 拿到行后避免脏数据）
        if (!priceAlertEvaluator.evaluate(alert, markPrice)) {
            return false;
        }
        int nextTriggerCount = alert.triggerCount() + 1;
        int nextStatusCode = nextTriggerCount >= MAX_TRIGGER_COUNT
                ? TradingPriceAlertStatus.EXHAUSTED.code()
                : TradingPriceAlertStatus.ACTIVE.code();
        int updated = priceAlertRepository.incrementTrigger(
                alertId, alert.triggerCount(), nextTriggerCount, nextStatusCode, markPrice);
        if (updated == 0) {
            log.warn("trading.price-alert.trigger.race id={} expected={}", alertId, alert.triggerCount());
            return false;
        }
        boolean exhausted = nextTriggerCount >= MAX_TRIGGER_COUNT;
        if (exhausted && priceAlertRepository.countActiveBySymbol(alert.symbol()) == 0) {
            triggerActivityRegistry.markPriceAlertInactive(alert.symbol());
        }
        PriceAlertTriggeredPayload payload = new PriceAlertTriggeredPayload(
                String.valueOf(alertId),
                alert.symbol(),
                alert.direction().name(),
                alert.targetPrice(),
                markPrice,
                nextTriggerCount,
                Math.max(0, MAX_TRIGGER_COUNT - nextTriggerCount),
                exhausted,
                alert.note(),
                OffsetDateTime.now());
        try {
            realtimePushService.publishPriceAlertTriggered(alert.userId(), payload);
        } catch (RuntimeException ex) {
            log.warn("trading.price-alert.push.failed id={} message={}", alertId, ex.getMessage());
        }
        if (notificationService != null) {
            String dirLabel = alert.direction().name().equals("ABOVE") ? "向上穿透" : "向下穿透";
            String exhaustSuffix = exhausted
                    ? "（已用完 3 次）"
                    : "（剩余 " + Math.max(0, MAX_TRIGGER_COUNT - nextTriggerCount) + " 次）";
            String noteSuffix = (alert.note() == null || alert.note().isBlank()) ? "" : "：" + alert.note();
            notificationService.send(
                    "PRICE_ALERT_TRIGGERED",
                    alert.userId(),
                    "PRICE_ALERT_TRIGGERED",
                    java.util.Map.of(
                            "symbol", alert.symbol(),
                            "direction", dirLabel,
                            "targetPrice", alert.targetPrice().toPlainString(),
                            "markPrice", markPrice.toPlainString(),
                            "exhaustSuffix", exhaustSuffix,
                            "noteSuffix", noteSuffix
                    ),
                    "PRICE_ALERT",
                    alertId,
                    null
            );
        }
        log.info("trading.price-alert.triggered id={} userId={} symbol={} triggerCount={} exhausted={}",
                alertId, alert.userId(), alert.symbol(), nextTriggerCount, exhausted);
        return true;
    }
}
